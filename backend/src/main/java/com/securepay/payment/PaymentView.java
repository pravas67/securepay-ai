package com.securepay.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** What the API and the AI agent see. Internal ids and user ids are deliberately left out. */
public record PaymentView(
        String reference,
        BigDecimal amount,
        String currency,
        String recipient,
        String description,
        String status,
        OffsetDateTime createdAt) {

    static PaymentView from(Payment p) {
        return new PaymentView(p.getReference(), p.getAmount(), p.getCurrency(), p.getRecipient(),
                p.getDescription(), p.getStatus(), p.getCreatedAt());
    }
}
