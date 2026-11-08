package com.securepay.payment;

public enum DraftKind {
    /** Card payment through the payment provider (sandbox or Stripe test mode). */
    PAYMENT,
    /** Transfer between two simulated wallets. */
    WALLET_TRANSFER
}
