package com.securepay.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securepay.auth")
public record AuthProperties(String jwtSecret, Duration tokenTtl, String issuer) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("securepay.auth.jwt-secret (JWT_SECRET) must be set to at least 32 characters");
        }
    }
}
