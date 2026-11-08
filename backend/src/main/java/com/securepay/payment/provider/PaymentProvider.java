package com.securepay.payment.provider;

import java.math.BigDecimal;

/**
 * The boundary to whoever actually moves money: {@link SandboxPaymentProvider} or {@link StripePaymentProvider}.
 *
 * <p>Implementations must be idempotent on {@code idempotencyKey}: calling twice with the same key must
 * return the first result and must not charge twice. Implementations throw {@link ProviderUnavailableException}
 * when the outcome is unknown (timeout, 5xx), so the caller can retry later with the same key.
 */
public interface PaymentProvider {

    ProviderResult charge(String idempotencyKey, BigDecimal amount, String currency, String recipient, String description);

    enum Status {
        SUCCEEDED,
        /** Accepted but not final yet; a webhook will report the outcome. */
        PENDING,
        DECLINED
    }

    record ProviderResult(Status status, String providerReference, String failureReason) {

        public static ProviderResult success(String providerReference) {
            return new ProviderResult(Status.SUCCEEDED, providerReference, null);
        }

        public static ProviderResult pending(String providerReference) {
            return new ProviderResult(Status.PENDING, providerReference, null);
        }

        public static ProviderResult declined(String reason) {
            return new ProviderResult(Status.DECLINED, null, reason);
        }
    }
}
