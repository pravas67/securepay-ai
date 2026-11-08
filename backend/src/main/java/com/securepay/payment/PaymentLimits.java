package com.securepay.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securepay.payments")
public record PaymentLimits(
        BigDecimal maxSingleAmount,
        BigDecimal rolling24hLimit,
        Duration approvalTtl,
        Set<String> supportedCurrencies) {
}
