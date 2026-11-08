package com.securepay.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

/** One ledger line. Negative amount = money out. Append-only (enforced by a database trigger). */
@Entity
@Immutable
@Table(name = "wallet_transaction")
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String reference;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private String counterparty;

    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected WalletTransaction() {
    }

    WalletTransaction(String reference, Wallet wallet, BigDecimal amount, String counterparty, String description) {
        this.reference = reference;
        this.walletId = wallet.getId();
        this.userId = wallet.getUserId();
        this.amount = amount;
        this.balanceAfter = wallet.getBalance();
        this.counterparty = counterparty;
        this.description = description;
        this.createdAt = OffsetDateTime.now();
    }

    public String getReference() { return reference; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getCounterparty() { return counterparty; }
    public String getDescription() { return description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
