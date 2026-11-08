package com.securepay.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Simulated wallet. Holds demo balances only; there is no link to real funds. */
@Entity
@Table(name = "wallet")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Wallet() {
    }

    Wallet(Long userId, String currency) {
        this.userId = userId;
        this.balance = BigDecimal.ZERO;
        this.currency = currency;
        this.updatedAt = OffsetDateTime.now();
    }

    /** Applies a signed amount (negative = debit). The DB CHECK (balance >= 0) is the last line of defence. */
    void apply(BigDecimal delta) {
        BigDecimal next = balance.add(delta);
        if (next.signum() < 0) {
            throw new IllegalStateException("Wallet balance would go negative");
        }
        this.balance = next;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
