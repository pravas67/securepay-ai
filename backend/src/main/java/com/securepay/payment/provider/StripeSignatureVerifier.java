package com.securepay.payment.provider;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the {@code Stripe-Signature} header: {@code t=<unix time>,v1=<hex HMAC-SHA256 of "t.payload">}.
 * Rejects bad signatures and anything older than the tolerance, which stops replayed webhooks.
 */
public class StripeSignatureVerifier {

    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final byte[] secret;
    private final Clock clock;

    public StripeSignatureVerifier(String webhookSecret, Clock clock) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("securepay.stripe.webhook-secret must be set when the Stripe provider is enabled");
        }
        this.secret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public boolean isValid(String payload, String header) {
        if (payload == null || header == null) {
            return false;
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - timestamp) > TOLERANCE.toSeconds()) {
            return false;
        }
        byte[] expected = HexFormat.of().formatHex(hmac(timestamp + "." + payload)).getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison so timing doesn't leak how much of a forged signature was right.
        return signatures.stream().anyMatch(s -> MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.UTF_8)));
    }

    public String sign(String payload, long timestamp) {
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hmac(timestamp + "." + payload));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
