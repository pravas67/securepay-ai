package com.securepay.payment;

import java.math.BigDecimal;

/** What the user gets back after approving a draft, for either kind. */
public record ApprovalResult(
        DraftKind kind,
        String draftReference,
        String reference,
        BigDecimal amount,
        String currency,
        String recipient,
        String description,
        String status) {

    static ApprovalResult forPayment(PaymentDraft draft, PaymentView payment) {
        return new ApprovalResult(DraftKind.PAYMENT, draft.getReference(), payment.reference(), payment.amount(),
                payment.currency(), payment.recipient(), payment.description(), payment.status());
    }

    static ApprovalResult forTransfer(PaymentDraft draft) {
        return new ApprovalResult(DraftKind.WALLET_TRANSFER, draft.getReference(), draft.getResultReference(), draft.getAmount(),
                draft.getCurrency(), draft.getRecipient(), draft.getDescription(), "SUCCEEDED");
    }
}
