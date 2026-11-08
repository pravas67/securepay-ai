package com.securepay.wallet;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WalletTransactionView(String reference, BigDecimal amount, BigDecimal balanceAfter,
                                    String counterparty, String description, OffsetDateTime createdAt) {

    static WalletTransactionView from(WalletTransaction t) {
        return new WalletTransactionView(t.getReference(), t.getAmount(), t.getBalanceAfter(),
                t.getCounterparty(), t.getDescription(), t.getCreatedAt());
    }
}
