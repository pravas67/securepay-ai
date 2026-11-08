package com.securepay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securepay.common.ConflictException;
import com.securepay.common.NotFoundException;
import com.securepay.support.PostgresTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PaymentApprovalIntegrationTest extends PostgresTestBase {

    @Autowired PaymentDraftService service;

    Long alice;
    Long bob;

    @BeforeEach
    void setUp() {
        resetMoneyState();
        alice = userId("alice");
        bob = userId("bob");
    }

    private DraftView prepare(Long userId, String amount, String recipient) {
        return service.prepare(userId, new BigDecimal(amount), "INR", recipient, "test");
    }

    private int paymentsTo(String recipient) {
        return jdbc.queryForObject("select count(*) from payment where recipient = ?", Integer.class, recipient);
    }

    private String draftStatus(String reference) {
        return jdbc.queryForObject("select status from payment_draft where reference = ?", String.class, reference);
    }

    @Test
    void approvingExecutesOnceAndRetriesReturnTheSamePayment() {
        DraftView draft = prepare(alice, "500", "Retry Recipient");

        ApprovalResult first = service.approve(alice, draft.reference());
        ApprovalResult retry = service.approve(alice, draft.reference());

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(retry.reference()).isEqualTo(first.reference());
        assertThat(paymentsTo("Retry Recipient")).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsProduceExactlyOnePayment() throws Exception {
        DraftView draft = prepare(alice, "750", "Race Recipient");
        List<String> outcomes = runConcurrently(10, () -> {
            try {
                return service.approve(alice, draft.reference()).reference();
            } catch (ConflictException e) {
                return "conflict"; // lost the race while the winner was still executing
            }
        });

        assertThat(paymentsTo("Race Recipient")).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.equals("conflict")).distinct()).hasSize(1);
    }

    @Test
    void anotherUserCannotApproveOrEvenSeeTheDraft() {
        DraftView draft = prepare(alice, "100", "Private Recipient");

        assertThatThrownBy(() -> service.approve(bob, draft.reference())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.reject(bob, draft.reference())).isInstanceOf(NotFoundException.class);
        assertThat(service.pending(bob)).isEmpty();
        assertThat(paymentsTo("Private Recipient")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where user_id = ? and action = 'PAYMENT_APPROVAL' and outcome = 'DENIED'",
                Integer.class, bob)).isPositive();
    }

    @Test
    void expiredDraftsCannotBeApproved() {
        DraftView draft = prepare(alice, "100", "Late Recipient");
        jdbc.update("update payment_draft set expires_at = now() - interval '1 second' where reference = ?", draft.reference());

        assertThatThrownBy(() -> service.approve(alice, draft.reference())).isInstanceOf(ConflictException.class);
        assertThat(draftStatus(draft.reference())).isEqualTo("EXPIRED");
        assertThat(paymentsTo("Late Recipient")).isZero();
    }

    @Test
    void rejectedDraftsCannotBeApprovedLater() {
        DraftView draft = prepare(alice, "100", "Rejected Recipient");
        service.reject(alice, draft.reference());

        assertThatThrownBy(() -> service.approve(alice, draft.reference())).isInstanceOf(ConflictException.class);
        assertThat(paymentsTo("Rejected Recipient")).isZero();
    }

    @Test
    void limitsAreEnforcedAtPrepareAndAgainAtApproval() {
        assertThatThrownBy(() -> prepare(alice, "10000.01", "Big Recipient")).isInstanceOf(PaymentRejectedException.class);
        assertThatThrownBy(() -> prepare(alice, "1.001", "Fractional Recipient")).isInstanceOf(PaymentRejectedException.class);
        assertThatThrownBy(() -> service.prepare(alice, new BigDecimal("100"), "USD", "Dollar Recipient", null))
                .isInstanceOf(PaymentRejectedException.class);

        // Three drafts that each fit the 20,000 rolling limit, but not all together.
        DraftView first = prepare(alice, "10000", "Limit A");
        DraftView second = prepare(alice, "10000", "Limit B");
        DraftView third = prepare(alice, "10000", "Limit C");
        service.approve(alice, first.reference());
        service.approve(alice, second.reference());

        assertThatThrownBy(() -> service.approve(alice, third.reference())).isInstanceOf(PaymentRejectedException.class);
        assertThat(paymentsTo("Limit C")).isZero();
    }

    @Test
    void declinedByProviderMarksDraftFailedAndCreatesNoPayment() {
        DraftView draft = prepare(alice, "100", "Please Decline Me");

        assertThatThrownBy(() -> service.approve(alice, draft.reference())).isInstanceOf(PaymentRejectedException.class);
        assertThat(draftStatus(draft.reference())).isEqualTo("FAILED");
        assertThat(paymentsTo("Please Decline Me")).isZero();
    }

    @Test
    void draftTermsCannotBeChangedAfterCreation() {
        DraftView draft = prepare(alice, "100", "Original Recipient");

        assertThatThrownBy(() -> jdbc.update("update payment_draft set amount = 9999 where reference = ?", draft.reference()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update payment_draft set recipient = 'Attacker' where reference = ?", draft.reference()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update payment_draft set kind = 'WALLET_TRANSFER' where reference = ?", draft.reference()))
                .hasMessageContaining("immutable");
    }

    @Test
    void tooManyPendingDraftsAreRefused() {
        for (int i = 0; i < PaymentDraftService.MAX_PENDING_DRAFTS; i++) {
            prepare(alice, "10", "Spam " + i);
        }
        assertThatThrownBy(() -> prepare(alice, "10", "One Too Many")).isInstanceOf(PaymentRejectedException.class);
    }

    @Test
    void interruptedApprovalIsFinishedByRecoveryExactlyOnce() {
        DraftView draft = prepare(alice, "300", "Crash Recipient");
        // Simulate a crash right after the claim: draft is EXECUTING, nothing recorded, claimed 5 minutes ago.
        jdbc.update("update payment_draft set status = 'EXECUTING', decided_at = now() - interval '5 minutes' where reference = ?", draft.reference());

        assertThat(service.recoverStuck(Duration.ofMinutes(2))).isEqualTo(1);
        assertThat(service.recoverStuck(Duration.ofMinutes(2))).isZero();

        assertThat(draftStatus(draft.reference())).isEqualTo("EXECUTED");
        assertThat(paymentsTo("Crash Recipient")).isEqualTo(1);
    }

    static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}
