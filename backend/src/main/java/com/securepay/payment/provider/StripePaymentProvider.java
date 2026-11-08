package com.securepay.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Stripe <b>test mode</b> via its REST API (PaymentIntents). Active when securepay.payments.provider=stripe.
 *
 * <p>Each charge confirms a PaymentIntent against Stripe's test card ({@code pm_card_visa} by default), so no real
 * card is involved. The recipient is stored as metadata; paying a real third party would use Stripe Connect.
 */
@Component
@ConditionalOnProperty(name = "securepay.payments.provider", havingValue = "stripe")
public class StripePaymentProvider implements PaymentProvider {

    private final RestClient client;
    private final StripeProperties properties;
    private final ObjectMapper objectMapper;

    public StripePaymentProvider(RestClient.Builder builder, StripeProperties properties, ObjectMapper objectMapper) {
        String key = properties.secretKey() == null ? "" : properties.secretKey();
        // Hard stop: this project must never be able to move real money.
        if (!key.startsWith("sk_test_") && !key.startsWith("rk_test_")) {
            throw new IllegalStateException("securepay.stripe.secret-key must be a Stripe TEST key (sk_test_… or rk_test_…)");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = builder
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + key)
                .build();
    }

    @Override
    public ProviderResult charge(String idempotencyKey, BigDecimal amount, String currency, String recipient, String description) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        // Stripe wants the smallest currency unit (paise for INR). Our currencies all have two decimals.
        form.add("amount", Long.toString(amount.movePointRight(2).longValueExact()));
        form.add("currency", currency.toLowerCase(Locale.ROOT));
        form.add("payment_method", properties.testPaymentMethod());
        form.add("confirm", "true");
        form.add("automatic_payment_methods[enabled]", "true");
        form.add("automatic_payment_methods[allow_redirects]", "never");
        form.add("metadata[recipient]", recipient);
        form.add("metadata[securepay_draft]", idempotencyKey);
        if (description != null) {
            form.add("description", description);
        }

        try {
            return client.post()
                    .uri("/v1/payment_intents")
                    // Stripe returns the original response for a repeated key instead of charging again.
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        JsonNode body = readJson(response.getBody().readAllBytes());
                        if (status >= 500) {
                            throw new ProviderUnavailableException("Stripe returned " + status, null);
                        }
                        if (status >= 400) {
                            // 402 = card declined; other 4xx = request rejected. Either way, nothing was charged.
                            return ProviderResult.declined(body.path("error").path("message").asText("Rejected by Stripe"));
                        }
                        return fromPaymentIntent(body);
                    });
        } catch (ResourceAccessException e) {
            throw new ProviderUnavailableException("Could not reach Stripe", e);
        }
    }

    static ProviderResult fromPaymentIntent(JsonNode intent) {
        String id = intent.path("id").asText();
        return switch (intent.path("status").asText()) {
            case "succeeded" -> ProviderResult.success(id);
            case "processing" -> ProviderResult.pending(id);
            default -> ProviderResult.declined(intent.path("last_payment_error").path("message")
                    .asText("Payment needs further action (" + intent.path("status").asText() + ")"));
        };
    }

    private JsonNode readJson(byte[] bytes) {
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException e) {
            throw new ProviderUnavailableException("Unreadable response from Stripe", e);
        }
    }
}
