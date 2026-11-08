package com.securepay.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

/** Append-only record. The database trigger rejects UPDATE and DELETE on this table. */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String action;

    private String details;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AuditEntry() {
    }

    AuditEntry(Long userId, String action, String details, AuditOutcome outcome) {
        this.userId = userId;
        this.action = action;
        this.details = details;
        this.outcome = outcome.name();
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getAction() { return action; }
    public String getDetails() { return details; }
    public String getOutcome() { return outcome; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
