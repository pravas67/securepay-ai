package com.securepay.wallet;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WalletView(BigDecimal balance, String currency, boolean simulated, OffsetDateTime updatedAt) {

    static WalletView from(Wallet w) {
        return new WalletView(w.getBalance(), w.getCurrency(), true, w.getUpdatedAt());
    }
}
