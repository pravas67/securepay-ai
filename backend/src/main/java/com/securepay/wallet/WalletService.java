package com.securepay.wallet;

import com.securepay.common.NotFoundException;
import com.securepay.payment.PaymentRejectedException;
import com.securepay.user.AppUser;
import com.securepay.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WalletService {

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final AppUserRepository users;

    public WalletService(WalletRepository wallets, WalletTransactionRepository transactions, AppUserRepository users) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.users = users;
    }

    public WalletView balance(Long userId) {
        return WalletView.from(walletOf(userId));
    }

    public List<WalletTransactionView> recentTransactions(Long userId) {
        return transactions.findTop20ByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(WalletTransactionView::from)
                .toList();
    }

    public BigDecimal sentSince(Long userId, OffsetDateTime since) {
        return transactions.sumDebitsSince(userId, since);
    }

    /**
     * Checks a proposed transfer and returns the normalised recipient username and the wallet currency.
     * Called when a draft is prepared, and again (inside {@link #transfer}) when it's executed.
     */
    public TransferCheck checkTransfer(Long fromUserId, String recipientUsername, BigDecimal amount) {
        Wallet from = walletOf(fromUserId);
        AppUser recipient = recipientUser(recipientUsername);
        if (recipient.getId().equals(fromUserId)) {
            throw new PaymentRejectedException("You can't transfer to your own wallet.");
        }
        Wallet to = walletOf(recipient.getId());
        if (!to.getCurrency().equals(from.getCurrency())) {
            throw new PaymentRejectedException("The recipient's wallet uses a different currency.");
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw new PaymentRejectedException("Insufficient demo wallet balance.");
        }
        return new TransferCheck(recipient.getUsername(), from.getCurrency());
    }

    /**
     * Moves demo funds between two wallets and writes both ledger lines. Must run inside the caller's
     * transaction, so the transfer and the draft's EXECUTED status commit together or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transfer(Long fromUserId, String recipientUsername, BigDecimal amount, String reference, String description) {
        AppUser sender = users.findById(fromUserId).orElseThrow();
        AppUser recipient = recipientUser(recipientUsername);
        // Lock both wallets in a fixed order (lowest user id first) so opposite transfers can't deadlock.
        boolean senderFirst = sender.getId() < recipient.getId();
        Wallet first = lock(senderFirst ? sender.getId() : recipient.getId());
        Wallet second = lock(senderFirst ? recipient.getId() : sender.getId());
        Wallet from = senderFirst ? first : second;
        Wallet to = senderFirst ? second : first;

        if (from.getBalance().compareTo(amount) < 0) {
            throw new PaymentRejectedException("Insufficient demo wallet balance.");
        }
        from.apply(amount.negate());
        to.apply(amount);
        transactions.save(new WalletTransaction(reference, from, amount.negate(), recipient.getUsername(), description));
        transactions.save(new WalletTransaction(reference, to, amount, sender.getUsername(), description));
    }

    /** Creates a wallet with demo funds and the matching opening ledger line. */
    @Transactional
    public void openDemoWallet(Long userId, BigDecimal openingBalance, String currency) {
        Wallet wallet = wallets.save(new Wallet(userId, currency));
        wallet.apply(openingBalance);
        transactions.save(new WalletTransaction("OPEN-" + userId, wallet, openingBalance, "Opening balance", "Demo funds"));
    }

    private Wallet lock(Long userId) {
        return wallets.lockByUserId(userId).orElseThrow(() -> new NotFoundException("No wallet for this user"));
    }

    private Wallet walletOf(Long userId) {
        return wallets.findByUserId(userId).orElseThrow(() -> new NotFoundException("No wallet for this user"));
    }

    private AppUser recipientUser(String username) {
        String normalised = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        return users.findByUsername(normalised)
                .orElseThrow(() -> new PaymentRejectedException("No demo wallet found for recipient '" + username + "'."));
    }

    public record TransferCheck(String recipientUsername, String currency) {
    }
}
