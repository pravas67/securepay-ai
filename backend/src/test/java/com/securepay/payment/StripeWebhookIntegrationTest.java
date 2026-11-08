package com.securepay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securepay.payment.provider.StripeSignatureVerifier;
import com.securepay.support.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "securepay.payments.provider=stripe",
        "securepay.stripe.secret-key=sk_test_dummy",
        "securepay.stripe.webhook-secret=whsec_test_secret"})
@AutoConfigureMockMvc
class StripeWebhookIntegrationTest extends PostgresTestBase {

    @Autowired MockMvc mvc;

    private final StripeSignatureVerifier signer = new StripeSignatureVerifier("whsec_test_secret", Clock.systemUTC());

    @BeforeEach
    void setUp() {
        resetMoneyState();
        jdbc.update("""
                insert into payment (reference, user_id, amount, currency, recipient, description, status, provider_reference)
                values ('PAY-HOOK0001', ?, 500, 'INR', 'Hook Recipient', 'test', 'PENDING', 'pi_test_123')
                """, userId("alice"));
    }

    private static String event(String id, String type, String intentId) {
        return """
                {"id":"%s","type":"%s","data":{"object":{"id":"%s","object":"payment_intent"}}}
                """.formatted(id, type, intentId);
    }

    private ResultActions send(String payload, String signature) throws Exception {
        return mvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature).content(payload));
    }

    private String paymentStatus() {
        return jdbc.queryForObject("select status from payment where reference = 'PAY-HOOK0001'", String.class);
    }

    @Test
    void signedEventUpdatesPendingPaymentOnceEvenIfDeliveredTwice() throws Exception {
        String payload = event("evt_1", "payment_intent.succeeded", "pi_test_123");
        String signature = signer.sign(payload, Instant.now().getEpochSecond());

        send(payload, signature).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("processed"));
        send(payload, signature).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("duplicate"));

        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void forgedOrTamperedEventsAreRejected() throws Exception {
        String payload = event("evt_2", "payment_intent.payment_failed", "pi_test_123");
        String signedForOtherPayload = signer.sign(event("evt_2", "payment_intent.succeeded", "pi_test_123"), Instant.now().getEpochSecond());
        StripeSignatureVerifier attacker = new StripeSignatureVerifier("whsec_wrong", Clock.systemUTC());

        send(payload, signedForOtherPayload).andExpect(status().isBadRequest());
        send(payload, attacker.sign(payload, Instant.now().getEpochSecond())).andExpect(status().isBadRequest());
        send(payload, "garbage").andExpect(status().isBadRequest());

        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    void replayedOldEventIsRejected() throws Exception {
        String payload = event("evt_3", "payment_intent.succeeded", "pi_test_123");
        String oldSignature = signer.sign(payload, Instant.now().minusSeconds(600).getEpochSecond());

        send(payload, oldSignature).andExpect(status().isBadRequest());
        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    void eventForUnknownPaymentAsksStripeToRetry() throws Exception {
        String payload = event("evt_4", "payment_intent.succeeded", "pi_not_recorded_yet");

        send(payload, signer.sign(payload, Instant.now().getEpochSecond())).andExpect(status().isConflict());
        // Not marked processed, so Stripe's retry will be handled.
        assertThat(jdbc.queryForObject("select count(*) from processed_webhook_event where event_id = 'evt_4'", Integer.class)).isZero();
    }
}
