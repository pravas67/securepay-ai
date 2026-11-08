package com.securepay.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class StripePaymentProviderTest {

    private static final StripeProperties PROPS = new StripeProperties("sk_test_abc", "whsec_x", "pm_card_visa", "https://api.stripe.test");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final StripePaymentProvider provider = new StripePaymentProvider(builder, PROPS, new ObjectMapper());

    @Test
    void sendsAmountInPaiseWithIdempotencyKeyAndMapsSuccess() {
        server.expect(requestTo("https://api.stripe.test/v1/payment_intents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "DRF-ABC"))
                .andExpect(header("Authorization", "Bearer sk_test_abc"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("amount=50050")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("currency=inr")))
                .andRespond(withSuccess("{\"id\":\"pi_1\",\"status\":\"succeeded\"}", MediaType.APPLICATION_JSON));

        var result = provider.charge("DRF-ABC", new BigDecimal("500.50"), "INR", "Shop", "test");

        assertThat(result.status()).isEqualTo(PaymentProvider.Status.SUCCEEDED);
        assertThat(result.providerReference()).isEqualTo("pi_1");
        server.verify();
    }

    @Test
    void processingIsPendingAndDeclineIsDeclined() {
        server.expect(requestTo("https://api.stripe.test/v1/payment_intents"))
                .andRespond(withSuccess("{\"id\":\"pi_2\",\"status\":\"processing\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.stripe.test/v1/payment_intents"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"card_declined\",\"message\":\"Your card was declined.\"}}"));

        assertThat(provider.charge("DRF-1", BigDecimal.TEN, "INR", "Shop", null).status()).isEqualTo(PaymentProvider.Status.PENDING);
        var declined = provider.charge("DRF-2", BigDecimal.TEN, "INR", "Shop", null);
        assertThat(declined.status()).isEqualTo(PaymentProvider.Status.DECLINED);
        assertThat(declined.failureReason()).isEqualTo("Your card was declined.");
    }

    @Test
    void serverErrorMeansOutcomeUnknownSoCallerRetriesLater() {
        server.expect(requestTo("https://api.stripe.test/v1/payment_intents")).andRespond(withServerError());

        assertThatThrownBy(() -> provider.charge("DRF-3", BigDecimal.TEN, "INR", "Shop", null))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void liveKeysAreRefused() {
        var live = new StripeProperties("sk_live_abc", "whsec_x", "pm_card_visa", "https://api.stripe.test");
        assertThatThrownBy(() -> new StripePaymentProvider(RestClient.builder(), live, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TEST key");
    }
}
