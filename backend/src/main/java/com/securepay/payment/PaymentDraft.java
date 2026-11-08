package com.securepay.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A proposed payment. Its terms (amount, currency, recipient, description) are fixed at creation;
 * there are no setters for them and a database trigger rejects any change.
 */
@Entity
@Table(name = "payment_draft")
public class PaymentDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftKind kind;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String recipient;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "result_reference")
    private String resultReference;

    protected PaymentDraft() {
    }

    PaymentDraft(String reference, Long userId, DraftKind kind, BigDecimal amount, String currency, String recipient,
                 String description, OffsetDateTime expiresAt) {
        this.reference = reference;
        this.userId = userId;
        this.kind = kind;
        this.amount = amount;
        this.currency = currency;
        this.recipient = recipient;
        this.description = description;
        this.status = DraftStatus.PENDING_APPROVAL;
        this.createdAt = OffsetDateTime.now();
        this.expiresAt = expiresAt;
    }

    boolean isExpired(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    void markExecuted(Long paymentId, String resultReference) {
        this.status = DraftStatus.EXECUTED;
        this.paymentId = paymentId;
        this.resultReference = resultReference;
    }

    void markFailed(String reason) {
        this.status = DraftStatus.FAILED;
        this.failureReason = reason;
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public Long getUserId() { return userId; }
    public DraftKind getKind() { return kind; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public String getResultReference() { return resultReference; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getRecipient() { return recipient; }
    public String getDescription() { return description; }
    public DraftStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public Long getPaymentId() { return paymentId; }
    public String getFailureReason() { return failureReason; }
}
