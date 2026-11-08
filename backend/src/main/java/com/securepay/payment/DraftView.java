package com.securepay.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record DraftView(
        String reference,
        DraftKind kind,
        BigDecimal amount,
        String currency,
        String recipient,
        String description,
        DraftStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        String failureReason) {

    static DraftView from(PaymentDraft d) {
        return new DraftView(d.getReference(), d.getKind(), d.getAmount(), d.getCurrency(), d.getRecipient(),
                d.getDescription(), d.getStatus(), d.getCreatedAt(), d.getExpiresAt(), d.getFailureReason());
    }
}
