package com.bhawana.lms.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * (Disbursement slice) — the Prometheus exposition is scraped through the real registry and
 * the real authentication boundary: anonymous callers get 401, non-admin roles get 403, and
 * SYSTEM_ADMIN receives the exposition including a known disbursement meter.
 *
 * <p>The shared test environment disables metrics export by default
 * ({@code management.defaults.metrics.export.enabled=false}); this class re-enables only the
 * Prometheus export, scoped to this test, so the scrape path is exercised without touching
 * shared fixtures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "management.prometheus.metrics.export.enabled=true")
class PrometheusScrapeTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void anonymousScrapeIsUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminScrapeIsForbidden() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdminScrapesKnownDisbursementMeter() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lms_disbursement_intent_unknown_count")))
                .andExpect(content().string(containsString("lms_disbursement_provider_initiate_latency")));
    }

    @Test
    void systemAdminScrapesReconciliationQueueAndStatusCheckMeters() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lms_disbursement_reconciliation_queue_count")))
                .andExpect(content().string(
                        containsString("lms_disbursement_reconciliation_queue_oldest_age_seconds")))
                .andExpect(content().string(containsString("lms_disbursement_provider_status_check_latency")));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
