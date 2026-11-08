package com.securepay.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepay.audit.AuditOutcome;
import com.securepay.audit.AuditService;
import com.securepay.payment.provider.StripeProperties;
import com.securepay.payment.provider.StripeSignatureVerifier;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Stripe events. Unauthenticated by design (Stripe can't log in), so trust comes only from the
 * HMAC signature. Each event id is processed once, even though Stripe may deliver it several times.
 */
@RestController
@ConditionalOnProperty(name = "securepay.payments.provider", havingValue = "stripe")
public class StripeWebhookController {

    private final StripeSignatureVerifier verifier;
    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public StripeWebhookController(StripeProperties properties, PaymentRepository payments, JdbcTemplate jdbc,
                                   ObjectMapper objectMapper, AuditService audit, PlatformTransactionManager transactionManager) {
        this.verifier = new StripeSignatureVerifier(properties.webhookSecret(), Clock.systemUTC());
        this.payments = payments;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PostMapping("/api/webhooks/stripe")
    ResponseEntity<Map<String, String>> receive(@RequestBody String payload,
                                                @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        if (!verifier.isValid(payload, signature)) {
            audit.record(null, "WEBHOOK_STRIPE", "invalid signature", AuditOutcome.DENIED);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid signature"));
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON"));
        }
        String eventId = event.path("id").asText();
        String type = event.path("type").asText();
        String newStatus = switch (type) {
            case "payment_intent.succeeded" -> "SUCCEEDED";
            case "payment_intent.payment_failed", "payment_intent.canceled" -> "FAILED";
            default -> null;
        };
        if (newStatus == null) {
            return ResponseEntity.ok(Map.of("result", "ignored"));
        }
        String intentId = event.path("data").path("object").path("id").asText();

        Outcome outcome = tx.execute(status -> {
            // Insert-first dedupe: a duplicate delivery hits the primary key and does nothing.
            int inserted = jdbc.update("insert into processed_webhook_event (event_id, type) values (?, ?) on conflict do nothing", eventId, type);
            if (inserted == 0) {
                return Outcome.DUPLICATE;
            }
            var payment = payments.findByProviderReference(intentId);
            if (payment.isEmpty()) {
                // We may not have recorded the payment yet. Roll back the dedupe row and let Stripe retry later.
                status.setRollbackOnly();
                return Outcome.UNKNOWN_PAYMENT;
            }
            Payment p = payment.get();
            // Only a pending payment changes on a webhook; a final status never flips back.
            if ("PENDING".equals(p.getStatus())) {
                p.updateStatus(newStatus);
                audit.record(p.getUserId(), "WEBHOOK_STRIPE", p.getReference() + " -> " + newStatus, AuditOutcome.SUCCESS);
            }
            return Outcome.PROCESSED;
        });

        return switch (outcome) {
            case DUPLICATE -> ResponseEntity.ok(Map.of("result", "duplicate"));
            case UNKNOWN_PAYMENT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Unknown payment, retry later"));
            case PROCESSED -> ResponseEntity.ok(Map.of("result", "processed"));
        };
    }

    private enum Outcome { DUPLICATE, UNKNOWN_PAYMENT, PROCESSED }
}
