package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.bhawana.lms.security.EdgeProperties;
import com.bhawana.lms.security.RateLimitFilter;
import com.bhawana.lms.security.RateLimitProperties;
import com.bhawana.lms.service.OpsAlertEmitters;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Full-stack embedded proof: the same assertions as the minimal socket-level tests, but
 * through the actual consumers over real HTTP — the real {@code AuthController} issuance
 * path, the real {@code LspSurfaceIpAllowlistFilter}, the real {@code RateLimitFilter}
 * (in-memory bucket store), and the real auth-audit rows read back over HTTP. The socket
 * peer here is always 127.0.0.1; trusted-proxy topologies are staged via explicit
 * {@link EdgeProperties} fixtures (restored afterwards).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeIpFullStackEmbeddedTest {

    private static final String PEER = "127.0.0.1";
    private static final String HARNESS_XFF = "198.51.100.7";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EdgeProperties edgeProperties;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    private final RestTemplate http = lenientHttp();
    private String adminToken;

    private static RestTemplate lenientHttp() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
            }
        });
        return template;
    }

    @AfterAll
    void cleanUp() {
        TenantScopedExecution.runAsAdmin(databaseCleaner::cleanIntegrationTestData);
    }

    @Test
    void untrustedPeerForgedHeadersIgnoredByRealAuthAllowlistAndAudit() throws Exception {
        Seed seed = seedLspAndClient();
        addApiCidr(seed.lspId(), "10.0.0.0/24");
        enforceApi(seed.lspId());

        HttpHeaders forged = new HttpHeaders();
        forged.add("X-Forwarded-For", "10.0.0.50");
        forged.add("X-Real-IP", "10.0.0.50");
        forged.add("Forwarded", "for=10.0.0.50");

        // Real issuance path attributes the socket peer, not the forged allowlisted header.
        ResponseEntity<String> denied = mintToken(seed, forged);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("API_CLIENT_IP_NOT_ALLOWED", codeOf(denied));

        // The allowlist deny itself writes no audit row (pre-existing behavior, unchanged);
        // the audit consumer below is proven on the attributed success path.
        assertTrue(auditIps(seed.clientId()).isEmpty());

        // Peer allowlisted: the same forged request now succeeds, still attributed to peer.
        addApiCidr(seed.lspId(), "127.0.0.1/32");
        ResponseEntity<String> allowed = mintToken(seed, forged);
        assertEquals(HttpStatus.OK, allowed.getStatusCode());

        List<String> ips = auditIps(seed.clientId());
        assertEquals(1, ips.size());
        assertEquals(PEER, ips.get(0));
    }

    @Test
    void trustedProxyAttributesRealClientAcrossAuthAllowlistAndAudit() throws Exception {
        Seed seed = seedLspAndClient();
        addApiCidr(seed.lspId(), "10.0.0.0/24");
        enforceApi(seed.lspId());

        withTrustedProxies(List.of("127.0.0.1/32"), () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Forwarded-For", "10.0.0.60");

            ResponseEntity<String> issued = mintToken(seed, headers);
            assertEquals(HttpStatus.OK, issued.getStatusCode());
            String token = accessTokenOf(issued);

            // Real audit consumer recorded the XFF client, not the proxy peer.
            List<String> ips = auditIps(seed.clientId());
            assertEquals(1, ips.size());
            assertEquals("10.0.0.60", ips.get(0));

            // Real allowlist filter enforces the same canonical IP in both directions.
            assertEquals(HttpStatus.FORBIDDEN, getFeed(token, "192.0.2.9").getStatusCode());
            assertEquals(HttpStatus.OK, getFeed(token, "10.0.0.61").getStatusCode());
        });
    }

    @Test
    void trustedAllowlistedProxyMalformedXffNeverAuthorizesAsProxy() throws Exception {
        Seed seed = seedLspAndClient();
        addApiCidr(seed.lspId(), "127.0.0.1/32");
        enforceApi(seed.lspId());

        withTrustedProxies(List.of("127.0.0.1/32"), () -> {
            HttpHeaders malformed = new HttpHeaders();
            malformed.add("X-Forwarded-For", "not-an-ip!!!");

            ResponseEntity<String> rejected = mintToken(seed, malformed);
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
            assertEquals("INVALID_FORWARDED_HEADER", codeOf(rejected));

            HttpHeaders duplicated = new HttpHeaders();
            duplicated.add("X-Forwarded-For", "10.0.0.50");
            duplicated.add("X-Forwarded-For", "10.0.0.51");

            ResponseEntity<String> ambiguous = mintToken(seed, duplicated);
            assertEquals(HttpStatus.BAD_REQUEST, ambiguous.getStatusCode());
            assertEquals("INVALID_FORWARDED_HEADER", codeOf(ambiguous));
        });
    }

    @Test
    void trustedAllowlistedProxyMissingXffIsRejected() throws Exception {
        // The edge always sets exactly one XFF line; a trusted peer that sends none must
        // not be attributed as itself (here allowlisted) — reject before routing.
        Seed seed = seedLspAndClient();
        addApiCidr(seed.lspId(), "127.0.0.1/32");
        enforceApi(seed.lspId());

        withTrustedProxies(List.of("127.0.0.1/32"), () -> {
            ResponseEntity<String> rejected = mintToken(seed, new HttpHeaders());
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
            assertEquals("INVALID_FORWARDED_HEADER", codeOf(rejected));
        });
    }

    @Test
    void rateLimiterBucketsOnCanonicalIpNotForgedHeaders() throws Exception {
        // Hammer /auth/login (IP-keyed, default 10/min) with a different forged XFF per
        // request. The 429 proves the real limiter bucketed the shared canonical peer.
        String email = "t03rate-" + UUID.randomUUID().toString().substring(0, 8) + "@bhawana.local";
        List<HttpStatus> statuses = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Forwarded-For", "198.51.100." + (i + 1));
            ResponseEntity<String> response = post(
                    "/api/v1/auth/login", Map.of("email", email, "password", "WrongPassword123!"), headers, null);
            statuses.add((HttpStatus) response.getStatusCode());
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
        }
        assertTrue(statuses.contains(HttpStatus.TOO_MANY_REQUESTS),
                "Expected a 429 once the shared canonical bucket was exhausted, got " + statuses);
        assertTrue(statuses.contains(HttpStatus.UNAUTHORIZED), "Expected 401s before the 429");

        // Every pre-429 attempt audited the peer despite the varying forged headers.
        List<String> ips = auditIps(email);
        assertEquals(statuses.stream().filter(s -> s == HttpStatus.UNAUTHORIZED).count(), ips.size());
        for (String ip : ips) {
            assertEquals(PEER, ip);
        }
    }

    private static HttpHeaders harnessHeaders() {
        HttpHeaders headers = new HttpHeaders();
        // Administrative harness traffic carries an explicit test XFF so it stays valid
        // under trusted topologies too (untrusted peer: ignored; trusted peer:
        // well-formed asserted client on paths with no allowlist gating).
        headers.add("X-Forwarded-For", HARNESS_XFF);
        return headers;
    }

    private ResponseEntity<String> mintToken(Seed seed, HttpHeaders headers) {
        return post("/api/v1/auth/token",
                Map.of("clientId", seed.clientId(), "clientSecret", seed.clientSecret()),
                headers, null);
    }

    private ResponseEntity<String> getFeed(String bearer, String xForwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", xForwardedFor);
        return http.exchange(url("/api/v1/lsp/loan-events"), HttpMethod.GET,
                new HttpEntity<>(headersWithBearer(headers, bearer)), String.class);
    }

    private List<String> auditIps(String username) throws Exception {
        HttpHeaders headers = harnessHeaders();
        String body = http.exchange(
                url("/api/v1/internal/ops/auth-audit?username=" + username + "&paginationDetails=true"),
                HttpMethod.GET, new HttpEntity<>(headersWithBearer(headers, adminToken())),
                String.class).getBody();
        List<String> ips = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(body).get("items")) {
            ips.add(item.get("actorIp").asText());
        }
        return ips;
    }

    private String adminToken() throws Exception {
        if (adminToken == null) {
            ResponseEntity<String> login = post("/api/v1/auth/login",
                    Map.of("email", "test.admin@bhawana.local", "password", "TestPassword123!"),
                    harnessHeaders(), null);
            assertEquals(HttpStatus.OK, login.getStatusCode(), "Bootstrap admin login failed: " + login.getBody());
            adminToken = objectMapper.readTree(login.getBody()).get("accessToken").asText();
        }
        return adminToken;
    }

    private Seed seedLspAndClient() throws Exception {
        String code = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ResponseEntity<String> lsp = post("/api/v1/internal/admin/lsps",
                Map.of("code", code, "name", "Test LSP " + code, "status", "ACTIVE"),
                harnessHeaders(), adminToken());
        assertEquals(HttpStatus.OK, lsp.getStatusCode(), lsp.getBody());
        String lspId = objectMapper.readTree(lsp.getBody()).get("id").asText();

        ResponseEntity<String> client = post("/api/v1/internal/admin/api-clients",
                Map.of("name", code + " client", "lspId", lspId, "status", "ACTIVE"),
                harnessHeaders(), adminToken());
        assertEquals(HttpStatus.OK, client.getStatusCode(), client.getBody());
        JsonNode clientJson = objectMapper.readTree(client.getBody());
        return new Seed(lspId, clientJson.get("clientId").asText(), clientJson.get("clientSecret").asText());
    }

    private void addApiCidr(String lspId, String cidr) {
        ResponseEntity<String> response = post(
                "/api/v1/internal/admin/lsps/" + lspId + "/api-ip-allowlist",
                Map.of("cidr", cidr, "description", "t03 test"), harnessHeaders(), adminTokenQuiet());
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
    }

    private void enforceApi(String lspId) {
        ResponseEntity<String> response = http.exchange(
                url("/api/v1/internal/admin/lsps/" + lspId + "/allowlist-enforcement"),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("enforceApi", true, "enforceUi", false),
                        jsonBearer(headersWithBearer(harnessHeaders(), adminTokenQuiet()))),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    }

    private String adminTokenQuiet() {
        try {
            return adminToken();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void withTrustedProxies(List<String> cidrs, ThrowingAction action) throws Exception {
        List<String> previous = List.copyOf(edgeProperties.getTrustedProxies());
        edgeProperties.setTrustedProxies(cidrs);
        try {
            action.run();
        } finally {
            edgeProperties.setTrustedProxies(previous);
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record Seed(String lspId, String clientId, String clientSecret) {
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders headers, String bearer) {
        return http.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, jsonBearer(headersWithBearer(headers, bearer))), String.class);
    }

    private HttpHeaders headersWithBearer(HttpHeaders headers, String bearer) {
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    private HttpHeaders jsonBearer(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String codeOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("code").asText();
    }

    private String accessTokenOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    /**
     * Same harness as the feed rate-limit test: the production {@link RateLimitFilter}
     * wired by {@code SecurityFilterChainConfig}, backed by an in-memory bucket store
     * instead of Redis (unavailable here).
     */
    @TestConfiguration
    static class RateLimitHarness {

        @Bean
        ProxyManager<String> rateLimitProxyManager() {
            return new LspLoanEventFeedRateLimitIntegrationTest.InMemoryProxyManager();
        }

        @Bean
        RateLimitFilter rateLimitFilter(
                ProxyManager<String> rateLimitProxyManager,
                ObjectMapper objectMapper,
                RateLimitProperties rateLimitProperties,
                ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider
        ) {
            return new RateLimitFilter(
                    rateLimitProxyManager, objectMapper, rateLimitProperties, opsAlertEmittersProvider);
        }
    }
}
