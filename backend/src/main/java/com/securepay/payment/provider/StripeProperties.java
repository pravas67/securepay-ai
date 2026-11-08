package com.securepay.payment.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securepay.stripe")
public record StripeProperties(String secretKey, String webhookSecret, String testPaymentMethod, String apiBaseUrl) {
}
