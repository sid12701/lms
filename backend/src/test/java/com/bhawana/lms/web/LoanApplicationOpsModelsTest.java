package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.service.LoanApplicationWebhookEventProjection;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoanApplicationOpsModelsTest {

    @Test
    void webhookEventDeliveryResponseMapsProjection() {
        LoanApplicationWebhookEventProjection projection = new LoanApplicationWebhookEventProjection(
                "event-1",
                "LOAN_CREATED",
                "https://example.test/hooks",
                "DELIVERED",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                202,
                null,
                Instant.parse("2026-01-01T00:00:01Z")
        );

        LoanApplicationOpsApiTypes.WebhookEventDeliveryResponse response =
                LoanApplicationOpsApiTypes.WebhookEventDeliveryResponse.from(projection);

        assertThat(response.eventId()).isEqualTo("event-1");
        assertThat(response.eventType()).isEqualTo("LOAN_CREATED");
        assertThat(response.status()).isEqualTo("DELIVERED");
    }

    @Test
    void mockDisbursementOutcomeRequestExposesOutcome() {
        MockDisbursementOutcomeRequest request =
                new MockDisbursementOutcomeRequest(MockDisbursementOutcome.PENDING_RECONCILIATION);

        assertThat(request.outcome()).isEqualTo(MockDisbursementOutcome.PENDING_RECONCILIATION);
    }
}
