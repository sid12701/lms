package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.security.RateLimitFilter;
import com.bhawana.lms.security.RateLimitProperties;
import com.bhawana.lms.service.OpsAlertEmitters;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AbstractCompareAndSwapBasedProxyManager;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AsyncCompareAndSwapOperation;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.CompareAndSwapOperation;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Issue 09: the partner loan event feed is rate limited per LSP, via the existing
 * {@code RateLimitFilter} + {@code KeyStrategy.LSP} path (application.yml rule {@code lsp-loan-events}),
 * not a limiter built for this endpoint.
 *
 * <p>The {@code test} profile runs with {@code app.rate-limit.enabled: false} (application-test.yml),
 * so {@code RateLimitConfig} never registers a {@code RateLimitFilter} bean and
 * {@code SecurityFilterChainConfig} adds nothing to the chain in any other test. Flipping that flag on
 * is not an option here: {@code RateLimitConfig} builds its own Lettuce client and connects to Redis
 * eagerly, and CI has no Redis service container. Instead, this class supplies its own
 * {@code RateLimitFilter} bean (via {@code @TestConfiguration}, which Spring Boot auto-registers for
 * {@code @SpringBootTest}) backed by an in-memory {@code ProxyManager}, and
 * {@code SecurityFilterChainConfig.securityFilterChain} — which takes an
 * {@code ObjectProvider<RateLimitFilter>} — picks it up and wires it into the real chain at the real
 * position. No production wiring changes to accommodate this test; only the token-bucket store is
 * swapped for one that doesn't require Redis.
 */
@SpringBootTest(properties = {
        "APP_RATE_LIMIT_LSP_FEED_PER_MINUTE=3",
        // Load-bearing, not defensive: onboarding a partner mints a token via POST /api/v1/auth/token,
        // which matches the auth-token rule (key IP, default 10/min) — and every MockMvc request shares
        // remote addr 127.0.0.1. At the default, onboarding two partners would throttle token minting
        // itself and this test would fail for an unrelated reason.
        "APP_RATE_LIMIT_AUTH_PER_MINUTE=1000",
        "APP_RATE_LIMIT_LSP_WRITE_PER_MINUTE=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LspLoanEventFeedRateLimitIntegrationTest {

    private static final String FEED_PATH = "/api/v1/lsp/loan-events";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    // This class provokes real RATE_LIMIT_BREACH ops-alert rows (OpsAlertEmitters.java, dedupe key
    // "rate-limit:lsp-loan-events:lsp:<uuid>", subjectId null) as a side effect of the very behaviour
    // under test — one per breached bucket. Clean up afterwards too, so they aren't left behind for a
    // later class that counts alerts (e.g. OpsAlertControllerTest, HomeDashboardControllerTest).
    //
    // Until D11 was fixed these rows were never written at all: the emitter's rule gate read
    // alert_rule on the tenant connection and threw "permission denied", which the filter swallowed.
    // breachingTheFeedBudgetRaisesARateLimitBreachOpsAlert is what keeps that honest.
    @AfterEach
    void tearDown() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void oneLspExceedingItsBudgetIsThrottledWhileAnotherIsServed() throws Exception {
        PartnerFixture a = onboardPartner("RATE-LIMIT-A");
        PartnerFixture b = onboardPartner("RATE-LIMIT-B");

        // Budget is 3/minute (APP_RATE_LIMIT_LSP_FEED_PER_MINUTE=3 above): the first three polls succeed.
        pollFeed(a).andExpect(status().isOk());
        pollFeed(a).andExpect(status().isOk());
        pollFeed(a).andExpect(status().isOk());

        // The fourth poll within the same minute exceeds A's budget: 429, with the same response shape
        // the rest of the LSP surface already uses for rate limiting (RateLimitFilter.writeApiError /
        // ApiError.of — both "code" and "error" carry RATE_LIMIT_EXCEEDED).
        MvcResult throttled = pollFeed(a)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        long retryAfterSeconds = Long.parseLong(throttled.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfterSeconds > 0, "Retry-After must be a positive number of seconds");

        // The core claim of this ticket: B's traffic is on its own budget. One LSP exceeding its budget
        // does not consume or block another LSP's requests.
        pollFeed(b).andExpect(status().isOk());

        // And B's successful poll must not have refilled or reset A's exhausted bucket.
        pollFeed(a)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"));
    }

    /**
     * D11 regression: the breach must reach the ops-alert inbox, not just the partner.
     *
     * <p>{@code RateLimitFilter} calls {@code OpsAlertEmitters.emitRateLimitBreach} on every rejection
     * and swallows any {@code RuntimeException} it throws, so a broken emitter is invisible from the
     * HTTP response alone — the 429 is correct either way. This asserts the row.
     */
    @Test
    void breachingTheFeedBudgetRaisesARateLimitBreachOpsAlert() throws Exception {
        PartnerFixture partner = onboardPartner("RATE-LIMIT-ALERT");

        pollFeed(partner).andExpect(status().isOk());
        pollFeed(partner).andExpect(status().isOk());
        pollFeed(partner).andExpect(status().isOk());
        pollFeed(partner).andExpect(status().isTooManyRequests());

        List<OpsAlert> breaches = rateLimitBreachAlerts();
        assertEquals(1, breaches.size(), "One RATE_LIMIT_BREACH alert expected for the throttled LSP");
        assertEquals(
                "rate-limit:lsp-loan-events:lsp:" + partner.lspId(),
                breaches.getFirst().getCorrelationId(),
                "Alert must be keyed on the per-LSP bucket that was breached"
        );

        // Deduped on that key while the alert is still NEW: a partner that keeps hammering a
        // budget it has already exhausted must not multiply rows in the inbox.
        pollFeed(partner).andExpect(status().isTooManyRequests());
        assertEquals(1, rateLimitBreachAlerts().size(), "Repeat breaches of one bucket must dedupe");
    }

    private List<OpsAlert> rateLimitBreachAlerts() {
        return TenantScopedExecution.callAsAdmin(() -> opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.RATE_LIMIT_BREACH)
                .toList());
    }

    private org.springframework.test.web.servlet.ResultActions pollFeed(PartnerFixture partner) throws Exception {
        return mockMvc.perform(get(FEED_PATH).header("Authorization", "Bearer " + partner.accessToken()));
    }

    /**
     * Deliberately minimal: only what is needed to mint an authenticated LSP API-client token that
     * carries an {@code lspId} claim, which is all {@code KeyStrategy.LSP} and the feed endpoint's
     * authorization need. No product, no product-LSP mapping, no application, no documents — the
     * limiter counts requests, not events, an empty feed still returns 200, and feed *content* is
     * already covered by {@code LspLoanEventFeedApiIntegrationTest}. Do not "fix" this by adding those
     * fixtures back in.
     */
    private PartnerFixture onboardPartner(String label) throws Exception {
        String code = label + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String lspId = objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult apiClientResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", code + " Integration",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode apiClient = objectMapper.readTree(apiClientResult.getResponse().getContentAsString());

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                apiClient.get("clientId").asText(),
                                apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        return new PartnerFixture(lspId, accessToken);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor systemAdmin() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private record PartnerFixture(String lspId, String accessToken) {
    }

    /**
     * Supplies the {@code RateLimitFilter} bean that {@code SecurityFilterChainConfig} looks for via
     * {@code ObjectProvider<RateLimitFilter>}, wired exactly as {@code RateLimitConfig.rateLimitFilter}
     * does in production — except the {@code ProxyManager} is the in-memory one below instead of the
     * Redis-backed one, since no Redis is available in this test context.
     */
    @TestConfiguration
    static class RateLimitTestConfig {

        @Bean
        ProxyManager<String> rateLimitProxyManager() {
            return new InMemoryProxyManager();
        }

        @Bean
        RateLimitFilter rateLimitFilter(
                ProxyManager<String> rateLimitProxyManager,
                ObjectMapper objectMapper,
                RateLimitProperties rateLimitProperties,
                ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider
        ) {
            return new RateLimitFilter(rateLimitProxyManager, objectMapper, rateLimitProperties, opsAlertEmittersProvider);
        }
    }

    /**
     * An in-memory stand-in for the Redis-backed {@code ProxyManager} bucket4j normally uses
     * (Bucket4jLettuce, see {@code RateLimitConfig}), for use only where no Redis is available. Extends
     * the same compare-and-swap base class the Redis implementation does, over a
     * {@code ConcurrentHashMap<String, byte[]>} in place of Redis keys — so the real bucket4j command
     * path, state (de)serialization, CAS retry loop, and greedy refill all execute unchanged; only the
     * storage backend differs.
     */
    static final class InMemoryProxyManager extends AbstractCompareAndSwapBasedProxyManager<String> {

        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        InMemoryProxyManager() {
            super(ClientSideConfig.getDefault());
        }

        @Override
        protected CompareAndSwapOperation beginCompareAndSwapOperation(String key) {
            return new CompareAndSwapOperation() {
                @Override
                public Optional<byte[]> getStateData(Optional<Long> requestTimeoutNanos) {
                    return Optional.ofNullable(store.get(key));
                }

                @Override
                public boolean compareAndSwap(
                        byte[] originalData,
                        byte[] newData,
                        RemoteBucketState newState,
                        Optional<Long> requestTimeoutNanos
                ) {
                    synchronized (store) {
                        byte[] current = store.get(key);
                        if (!Arrays.equals(current, originalData)) {
                            return false;
                        }
                        store.put(key, newData);
                        return true;
                    }
                }
            };
        }

        @Override
        protected AsyncCompareAndSwapOperation beginAsyncCompareAndSwapOperation(String key) {
            throw new UnsupportedOperationException("Async mode is not supported by this in-memory test double.");
        }

        @Override
        protected CompletableFuture<Void> removeAsync(String key) {
            throw new UnsupportedOperationException("Async mode is not supported by this in-memory test double.");
        }

        @Override
        public void removeProxy(String key) {
            store.remove(key);
        }

        @Override
        public boolean isAsyncModeSupported() {
            return false;
        }
    }
}
