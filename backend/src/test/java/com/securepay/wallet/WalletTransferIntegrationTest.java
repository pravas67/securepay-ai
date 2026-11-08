package com.securepay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securepay.payment.ApprovalResult;
import com.securepay.payment.DraftKind;
import com.securepay.payment.DraftView;
import com.securepay.payment.PaymentDraftService;
import com.securepay.payment.PaymentRejectedException;
import com.securepay.support.PostgresTestBase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WalletTransferIntegrationTest extends PostgresTestBase {

    @Autowired PaymentDraftService drafts;
    @Autowired WalletService wallets;

    Long alice;
    Long bob;

    @BeforeEach
    void setUp() {
        resetMoneyState();
        alice = userId("alice");
        bob = userId("bob");
    }

    private BigDecimal balance(Long userId) {
        return wallets.balance(userId).balance();
    }

    private BigDecimal ledgerSum(Long userId) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from wallet_transaction where user_id = ?", BigDecimal.class, userId);
    }

    @Test
    void approvedTransferMovesFundsAndWritesBothLedgerLines() {
        DraftView draft = drafts.prepareWalletTransfer(alice, new BigDecimal("1500"), "Bob", "Dinner");
        assertThat(draft.kind()).isEqualTo(DraftKind.WALLET_TRANSFER);
        assertThat(draft.recipient()).isEqualTo("bob");

        ApprovalResult result = drafts.approve(alice, draft.reference());

        assertThat(result.reference()).startsWith("TRF-");
        assertThat(balance(alice)).isEqualByComparingTo("23500.00");
        assertThat(balance(bob)).isEqualByComparingTo("5700.00");
        assertThat(jdbc.queryForObject("select count(*) from wallet_transaction where reference = ?", Integer.class, result.reference())).isEqualTo(2);
        // Retrying returns the same transfer and doesn't move money again.
        assertThat(drafts.approve(alice, draft.reference()).reference()).isEqualTo(result.reference());
        assertThat(balance(alice)).isEqualByComparingTo("23500.00");
    }

    @Test
    void invalidTransfersAreRefusedAtPrepare() {
        assertThatThrownBy(() -> drafts.prepareWalletTransfer(alice, new BigDecimal("10"), "alice", null))
                .isInstanceOf(PaymentRejectedException.class).hasMessageContaining("own wallet");
        assertThatThrownBy(() -> drafts.prepareWalletTransfer(alice, new BigDecimal("10"), "mallory", null))
                .isInstanceOf(PaymentRejectedException.class);
        assertThatThrownBy(() -> drafts.prepareWalletTransfer(bob, new BigDecimal("5000"), "alice", null))
                .isInstanceOf(PaymentRejectedException.class).hasMessageContaining("Insufficient");
    }

    @Test
    void balanceIsRecheckedAtApprovalTime() {
        // Two drafts that each fit bob's 4,200 balance but not together.
        DraftView first = drafts.prepareWalletTransfer(bob, new BigDecimal("3000"), "alice", null);
        DraftView second = drafts.prepareWalletTransfer(bob, new BigDecimal("3000"), "alice", null);
        drafts.approve(bob, first.reference());

        assertThatThrownBy(() -> drafts.approve(bob, second.reference())).isInstanceOf(PaymentRejectedException.class);
        assertThat(jdbc.queryForObject("select status from payment_draft where reference = ?", String.class, second.reference())).isEqualTo("FAILED");
        assertThat(balance(bob)).isEqualByComparingTo("1200.00");
    }

    @Test
    void opposingConcurrentTransfersNeitherDeadlockNorLoseMoney() throws Exception {
        List<String> refs = new ArrayList<>();
        List<Long> owners = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            refs.add(drafts.prepareWalletTransfer(alice, new BigDecimal("100"), "bob", null).reference());
            owners.add(alice);
            refs.add(drafts.prepareWalletTransfer(bob, new BigDecimal("100"), "alice", null).reference());
            owners.add(bob);
        }
        BigDecimal totalBefore = balance(alice).add(balance(bob));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(refs.size());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            Long owner = owners.get(i);
            String ref = refs.get(i);
            futures.add(pool.submit(() -> {
                start.await();
                return drafts.approve(owner, ref);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(balance(alice).add(balance(bob))).isEqualByComparingTo(totalBefore);
        assertThat(balance(alice)).isEqualByComparingTo("25000.00");
        // Ledger and balances agree (no opening rows after reset, so the ledger nets to the change: zero).
        assertThat(ledgerSum(alice)).isEqualByComparingTo("0");
    }

    @Test
    void ledgerRowsCannotBeAlteredOrDeleted() {
        DraftView draft = drafts.prepareWalletTransfer(alice, new BigDecimal("10"), "bob", null);
        drafts.approve(alice, draft.reference());

        assertThatThrownBy(() -> jdbc.update("update wallet_transaction set amount = 999999")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from wallet_transaction")).hasMessageContaining("append-only");
    }
}
