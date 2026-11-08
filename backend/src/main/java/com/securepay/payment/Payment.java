package com.securepay.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String recipient;

    private String description;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "provider_reference")
    private String providerReference;

    protected Payment() {
    }

    Payment(String reference, Long userId, BigDecimal amount, String currency, String recipient,
            String description, String status, String providerReference) {
        this.reference = reference;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.recipient = recipient;
        this.description = description;
        this.status = status;
        this.providerReference = providerReference;
        this.createdAt = OffsetDateTime.now();
    }

    void updateStatus(String status) {
        this.status = status;
    }

    public String getProviderReference() { return providerReference; }
    public Long getId() { return id; }
    public String getReference() { return reference; }
    public Long getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getRecipient() { return recipient; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
