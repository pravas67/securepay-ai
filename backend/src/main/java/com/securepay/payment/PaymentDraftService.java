package com.securepay.payment;

import com.securepay.audit.AuditOutcome;
import com.securepay.audit.AuditService;
import com.securepay.common.ConflictException;
import com.securepay.common.NotFoundException;
import com.securepay.payment.provider.PaymentProvider;
import com.securepay.payment.provider.PaymentProvider.ProviderResult;
import com.securepay.payment.provider.ProviderUnavailableException;
import com.securepay.user.AppUserRepository;
import com.securepay.wallet.WalletService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Prepare → approve → execute, for card payments and wallet transfers alike. Preparing only writes a draft.
 * Approving is the single path that moves money, and it can only be triggered by the signed-in user through
 * the REST API, never by the AI.
 */
@Service
public class PaymentDraftService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDraftService.class);

    static final int MAX_PENDING_DRAFTS = 5;
    private static final char[] REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final PaymentDraftRepository drafts;
    private final PaymentRepository payments;
    private final AppUserRepository users;
    private final WalletService walletService;
    private final PaymentProvider provider;
    private final PaymentLimits limits;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    public PaymentDraftService(PaymentDraftRepository drafts, PaymentRepository payments, AppUserRepository users,
                               WalletService walletService, PaymentProvider provider, PaymentLimits limits,
                               AuditService audit, PlatformTransactionManager transactionManager) {
        this.drafts = drafts;
        this.payments = payments;
        this.users = users;
        this.walletService = walletService;
        this.provider = provider;
        this.limits = limits;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Drafts a card payment. */
    public DraftView prepare(Long userId, BigDecimal amount, String currency, String recipient, String description) {
        return create(userId, DraftKind.PAYMENT, amount, description, () -> new Terms(
                validateCurrency(currency), validateText(recipient, "Recipient", 128, true)));
    }

    /** Drafts a transfer from the user's demo wallet to another user's demo wallet. */
    public DraftView prepareWalletTransfer(Long userId, BigDecimal amount, String recipientUsername, String description) {
        return create(userId, DraftKind.WALLET_TRANSFER, amount, description, () -> {
            validateAmount(amount);
            WalletService.TransferCheck check = walletService.checkTransfer(userId, recipientUsername, amount);
            return new Terms(validateCurrency(check.currency()), check.recipientUsername());
        });
    }

    private record Terms(String currency, String recipient) {
    }

    private interface TermsValidator {
        Terms validate();
    }

    private DraftView create(Long userId, DraftKind kind, BigDecimal amount, String description, TermsValidator kindSpecific) {
        String action = kind == DraftKind.PAYMENT ? "PAYMENT_PREPARED" : "TRANSFER_PREPARED";
        try {
            validateAmount(amount);
            Terms terms = kindSpecific.validate();
            String safeDescription = validateText(description, "Description", 255, false);

            OffsetDateTime now = OffsetDateTime.now();
            long pending = drafts.countByUserIdAndStatusAndExpiresAtAfter(userId, DraftStatus.PENDING_APPROVAL, now);
            if (pending >= MAX_PENDING_DRAFTS) {
                throw new PaymentRejectedException("You already have " + pending + " requests awaiting approval. Approve or reject those first.");
            }
            // Advisory check so the user isn't asked to approve something that can't succeed.
            // The authoritative check happens again at approval time, under a lock.
            if (spentInLast24h(userId).add(amount).compareTo(limits.rolling24hLimit()) > 0) {
                throw new PaymentRejectedException(overLimitMessage());
            }

            PaymentDraft draft = drafts.save(new PaymentDraft(newReference("DRF"), userId, kind, amount, terms.currency(),
                    terms.recipient(), safeDescription, now.plus(limits.approvalTtl())));
            audit.record(userId, action, describe(draft), AuditOutcome.SUCCESS);
            return DraftView.from(draft);
        } catch (PaymentRejectedException e) {
            audit.record(userId, action, e.getMessage(), AuditOutcome.DENIED);
            throw e;
        }
    }

    public List<DraftView> pending(Long userId) {
        return drafts.findTop20ByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(userId, DraftStatus.PENDING_APPROVAL, OffsetDateTime.now())
                .stream()
                .map(DraftView::from)
                .toList();
    }

    /**
     * Executes the draft exactly once. Safe to retry: approving an already-executed draft returns the
     * same result instead of paying again, and concurrent approvals can't both win.
     */
    public ApprovalResult approve(Long userId, String reference) {
        PaymentDraft draft = findOrAuditDenied(userId, reference, "PAYMENT_APPROVAL");
        if (draft.getStatus() == DraftStatus.EXECUTED) {
            return resultFor(draft);
        }

        // Step 1: claim the draft in a short transaction (no network calls while holding locks).
        ClaimOutcome outcome = tx.execute(status -> claim(userId, reference));
        switch (outcome) {
            case ALREADY_EXECUTED -> {
                return resultFor(find(userId, reference));
            }
            case EXPIRED -> {
                audit.record(userId, "PAYMENT_APPROVAL", reference + " expired", AuditOutcome.DENIED);
                throw new ConflictException("This request expired. Ask for a new one.");
            }
            case OVER_LIMIT -> {
                audit.record(userId, "PAYMENT_APPROVAL", reference + " over 24h limit", AuditOutcome.DENIED);
                throw new PaymentRejectedException(overLimitMessage());
            }
            case NOT_APPROVABLE -> {
                PaymentDraft current = find(userId, reference);
                audit.record(userId, "PAYMENT_APPROVAL", reference + " is " + current.getStatus(), AuditOutcome.DENIED);
                throw new ConflictException("This request can't be approved: it is " + current.getStatus() + ".");
            }
            case CLAIMED -> { /* continue below */ }
        }

        // Steps 2 and 3: execute and record.
        try {
            return executeClaimed(find(userId, reference));
        } catch (ProviderUnavailableException e) {
            log.warn("Provider unavailable for draft {}", reference, e);
            audit.record(userId, "PAYMENT_EXECUTED", reference + ": provider unavailable, will retry", AuditOutcome.FAILURE);
            throw new ConflictException("The payment provider didn't respond. It will be retried automatically; check your payments shortly.");
        }
    }

    public DraftView reject(Long userId, String reference) {
        findOrAuditDenied(userId, reference, "PAYMENT_REJECTED_BY_USER");
        Integer changed = tx.execute(status -> drafts.transition(reference, userId,
                DraftStatus.PENDING_APPROVAL, DraftStatus.REJECTED, OffsetDateTime.now()));
        PaymentDraft draft = find(userId, reference);
        if (changed == null || changed == 0) {
            throw new ConflictException("This request can't be rejected: it is " + draft.getStatus() + ".");
        }
        audit.record(userId, "PAYMENT_REJECTED_BY_USER", reference, AuditOutcome.SUCCESS);
        return DraftView.from(draft);
    }

    /**
     * Finishes drafts that were claimed but never completed, e.g. because the server stopped between the
     * provider call and recording its result. Safe because the provider call is idempotent on the draft
     * reference and recording only happens while the draft is still EXECUTING.
     */
    public int recoverStuck(Duration olderThan) {
        List<PaymentDraft> stuck = drafts.findTop20ByStatusAndDecidedAtBefore(DraftStatus.EXECUTING, OffsetDateTime.now().minus(olderThan));
        int recovered = 0;
        for (PaymentDraft draft : stuck) {
            try {
                executeClaimed(draft);
                recovered++;
            } catch (PaymentRejectedException e) {
                recovered++; // recorded as FAILED
            } catch (RuntimeException e) {
                log.warn("Recovery of draft {} failed; will try again", draft.getReference(), e);
            }
        }
        return recovered;
    }

    private ApprovalResult executeClaimed(PaymentDraft draft) {
        return draft.getKind() == DraftKind.WALLET_TRANSFER ? executeTransfer(draft) : executePayment(draft);
    }

    private ApprovalResult executePayment(PaymentDraft draft) {
        // No transaction around the network call. The draft reference is the idempotency key, so
        // a retry (by the user or the recovery job) can never charge twice.
        ProviderResult result = provider.charge(draft.getReference(), draft.getAmount(), draft.getCurrency(),
                draft.getRecipient(), draft.getDescription());

        ApprovalResult approval = tx.execute(status -> recordPayment(draft.getId(), result));
        if (approval == null) {
            audit.record(draft.getUserId(), "PAYMENT_EXECUTED", draft.getReference() + ": " + result.failureReason(), AuditOutcome.FAILURE);
            throw new PaymentRejectedException("The payment provider declined this payment: " + result.failureReason());
        }
        audit.record(draft.getUserId(), "PAYMENT_EXECUTED",
                draft.getReference() + " -> " + approval.reference() + " " + describe(draft) + " [" + approval.status() + "]", AuditOutcome.SUCCESS);
        return approval;
    }

    /** Returns the result, or null if the provider declined. */
    private ApprovalResult recordPayment(Long draftId, ProviderResult result) {
        PaymentDraft draft = drafts.findById(draftId).orElseThrow();
        if (draft.getStatus() != DraftStatus.EXECUTING) {
            // Someone else (user retry or recovery job) already recorded it.
            return draft.getStatus() == DraftStatus.EXECUTED ? resultFor(draft) : null;
        }
        if (result.status() == PaymentProvider.Status.DECLINED) {
            draft.markFailed(result.failureReason());
            return null;
        }
        Payment payment = payments.save(new Payment(newReference("PAY"), draft.getUserId(), draft.getAmount(),
                draft.getCurrency(), draft.getRecipient(), draft.getDescription(), result.status().name(), result.providerReference()));
        draft.markExecuted(payment.getId(), payment.getReference());
        return ApprovalResult.forPayment(draft, PaymentView.from(payment));
    }

    private ApprovalResult executeTransfer(PaymentDraft claimed) {
        String transferReference = newReference("TRF");
        try {
            // One transaction: ledger lines, both balances, and the draft status commit together.
            ApprovalResult result = tx.execute(status -> {
                PaymentDraft draft = drafts.findById(claimed.getId()).orElseThrow();
                if (draft.getStatus() != DraftStatus.EXECUTING) {
                    return draft.getStatus() == DraftStatus.EXECUTED ? resultFor(draft) : null;
                }
                walletService.transfer(draft.getUserId(), draft.getRecipient(), draft.getAmount(), transferReference, draft.getDescription());
                draft.markExecuted(null, transferReference);
                return ApprovalResult.forTransfer(draft);
            });
            if (result == null) {
                throw new ConflictException("This transfer was not completed.");
            }
            audit.record(claimed.getUserId(), "TRANSFER_EXECUTED", claimed.getReference() + " -> " + result.reference() + " " + describe(claimed), AuditOutcome.SUCCESS);
            return result;
        } catch (PaymentRejectedException e) {
            // The transfer transaction rolled back; record the failure in a fresh one.
            tx.executeWithoutResult(status -> drafts.findById(claimed.getId()).orElseThrow().markFailed(e.getMessage()));
            audit.record(claimed.getUserId(), "TRANSFER_EXECUTED", claimed.getReference() + ": " + e.getMessage(), AuditOutcome.FAILURE);
            throw e;
        }
    }

    private enum ClaimOutcome { CLAIMED, ALREADY_EXECUTED, EXPIRED, OVER_LIMIT, NOT_APPROVABLE }

    /** Runs inside a transaction holding the user's row lock, so one user's approvals are processed one at a time. */
    private ClaimOutcome claim(Long userId, String reference) {
        users.lockById(userId).orElseThrow();
        PaymentDraft draft = find(userId, reference);
        OffsetDateTime now = OffsetDateTime.now();

        if (draft.getStatus() == DraftStatus.EXECUTED) {
            return ClaimOutcome.ALREADY_EXECUTED;
        }
        if (draft.getStatus() != DraftStatus.PENDING_APPROVAL) {
            return ClaimOutcome.NOT_APPROVABLE;
        }
        if (draft.isExpired(now)) {
            drafts.transition(reference, userId, DraftStatus.PENDING_APPROVAL, DraftStatus.EXPIRED, now);
            return ClaimOutcome.EXPIRED;
        }
        if (spentInLast24h(userId).add(draft.getAmount()).compareTo(limits.rolling24hLimit()) > 0) {
            return ClaimOutcome.OVER_LIMIT;
        }
        // Conditional update: even without the lock, only one request could move PENDING -> EXECUTING.
        int claimed = drafts.transition(reference, userId, DraftStatus.PENDING_APPROVAL, DraftStatus.EXECUTING, now);
        return claimed == 1 ? ClaimOutcome.CLAIMED : ClaimOutcome.NOT_APPROVABLE;
    }

    private PaymentDraft find(Long userId, String reference) {
        return drafts.findByReferenceAndUserId(reference, userId)
                .orElseThrow(() -> new NotFoundException("Request " + reference + " not found"));
    }

    /** Like {@link #find}, but records the miss: it may be someone probing for another user's drafts. */
    private PaymentDraft findOrAuditDenied(Long userId, String reference, String action) {
        try {
            return find(userId, reference);
        } catch (NotFoundException e) {
            audit.record(userId, action, reference + " not found", AuditOutcome.DENIED);
            throw e;
        }
    }

    private ApprovalResult resultFor(PaymentDraft draft) {
        if (draft.getKind() == DraftKind.WALLET_TRANSFER) {
            return ApprovalResult.forTransfer(draft);
        }
        return ApprovalResult.forPayment(draft, payments.findById(draft.getPaymentId()).map(PaymentView::from).orElseThrow());
    }

    /** Card payments, wallet transfers sent, and anything currently executing, over the last 24 hours. */
    private BigDecimal spentInLast24h(Long userId) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(24);
        return payments.sumSpentSince(userId, since)
                .add(walletService.sentSince(userId, since))
                .add(drafts.sumExecuting(userId));
    }

    private String overLimitMessage() {
        return "This would exceed your 24-hour limit of " + limits.rolling24hLimit().toPlainString() + ".";
    }

    private String validateCurrency(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!limits.supportedCurrencies().contains(code)) {
            throw new PaymentRejectedException("Unsupported currency. Supported: " + String.join(", ", limits.supportedCurrencies()) + ".");
        }
        return code;
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentRejectedException("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new PaymentRejectedException("Amount can have at most two decimal places.");
        }
        if (amount.compareTo(limits.maxSingleAmount()) > 0) {
            throw new PaymentRejectedException("Amount exceeds the single-payment limit of " + limits.maxSingleAmount().toPlainString() + ".");
        }
    }

    private static String validateText(String value, String field, int maxLength, boolean required) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            if (required) {
                throw new PaymentRejectedException(field + " is required.");
            }
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new PaymentRejectedException(field + " must be at most " + maxLength + " characters.");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new PaymentRejectedException(field + " contains invalid characters.");
        }
        return trimmed;
    }

    private String newReference(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('-');
        for (int i = 0; i < 8; i++) {
            sb.append(REFERENCE_ALPHABET[random.nextInt(REFERENCE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String describe(PaymentDraft d) {
        return d.getReference() + " " + d.getKind() + " " + d.getAmount().toPlainString() + " " + d.getCurrency() + " to " + d.getRecipient();
    }
}
