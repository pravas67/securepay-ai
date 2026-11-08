package com.securepay.payment.provider;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for a real payment provider. Never contacts any network. Active unless
 * securepay.payments.provider=stripe. A recipient name containing "decline" is declined, so the failure
 * path can be exercised.
 */
@Component
@ConditionalOnProperty(name = "securepay.payments.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentProvider implements PaymentProvider {

    private final Map<String, ProviderResult> resultsByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public ProviderResult charge(String idempotencyKey, BigDecimal amount, String currency, String recipient, String description) {
        return resultsByIdempotencyKey.computeIfAbsent(idempotencyKey, key ->
                recipient.toLowerCase(Locale.ROOT).contains("decline")
                        ? ProviderResult.declined("Sandbox: card declined")
                        : ProviderResult.success("sbx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
    }
}
