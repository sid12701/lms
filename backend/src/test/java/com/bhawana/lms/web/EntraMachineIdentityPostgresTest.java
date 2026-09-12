package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.security.ApiClientJwtSessionValidator;
import com.bhawana.lms.security.EntraMachineJwtValidator;
import com.bhawana.lms.security.ManagedUserJwtPrincipalResolver;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.security.TrustedJwksJwtDecoderFactory;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Disabled-by-default Entra mapping/validators with real RSA tokens and a local JWKS HTTP server.
 * Full cutover remains externally blocked; these tests prove capability only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class EntraMachineIdentityPostgresTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String APP_CLIENT = "22222222-2222-2222-2222-222222222222";
    private static final String API_AUDIENCE = "33333333-3333-3333-3333-333333333333";
    private static final String ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";
    private static final String APP_ROLE = "Lms.Machine.Access";

    private static final RSAKey RSA_KEY_A;
    private static final RSAKey RSA_KEY_B;
    private static volatile String jwksJson;
    private static final AtomicInteger JWKS_HTTP_HITS = new AtomicInteger();
    private static volatile boolean jwksOutage;
    private static final HttpServer JWKS_SERVER;
    private static final String JWKS_URI;

    static {
        try {
            RSA_KEY_A = new RSAKeyGenerator(2048).keyID("kid-a").generate();
            RSA_KEY_B = new RSAKeyGenerator(2048).keyID("kid-b").generate();
            publishJwks(RSA_KEY_A);
            JWKS_SERVER = HttpServer.create(new InetSocketAddress(0), 0);
            JWKS_SERVER.createContext("/jwks", exchange -> {
                JWKS_HTTP_HITS.incrementAndGet();
                if (jwksOutage) {
                    byte[] body = "jwks unavailable".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, body.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                    return;
                }
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            JWKS_SERVER.start();
            JWKS_URI = "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks";
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired private com.bhawana.lms.repo.LspIpAllowlistRepository apiAllowlistRepository;
    @Autowired private com.bhawana.lms.security.LspSurfaceIpAllowlistFilter ipAllowlistFilter;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private EntraMachineJwtValidator entraMachineJwtValidator;
    @Autowired private SecurityProperties securityProperties;
    @Autowired private LspRepository lspRepository;
    @Autowired private ApiClientRepository apiClientRepository;
    @Autowired private ApiClientManagementService apiClientManagementService;
    @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    private UUID lspAId;
    private UUID lspBId;
    private UUID mappedClientId;

    @DynamicPropertySource
    static void entraProperties(DynamicPropertyRegistry registry) {
        registry.add("app.security.entra-machine-identity.enabled", () -> "true");
        registry.add("app.security.entra-machine-identity.trusted-tenant-id", () -> TENANT);
        registry.add("app.security.entra-machine-identity.issuer", () -> ISSUER);
        registry.add("app.security.entra-machine-identity.api-audience", () -> API_AUDIENCE);
        registry.add("app.security.entra-machine-identity.jwks-uri", () -> JWKS_URI);
        registry.add("app.security.entra-machine-identity.required-app-role", () -> APP_ROLE);
        registry.add("app.security.entra-machine-identity.jwks-cache-ttl", () -> "PT1S");
        registry.add("app.security.entra-machine-identity.unknown-kid-min-interval", () -> "PT2S");
        registry.add("app.security.entra-machine-identity.connect-timeout", () -> "PT2S");
        registry.add("app.security.entra-machine-identity.read-timeout", () -> "PT2S");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        JWKS_HTTP_HITS.set(0);
        jwksOutage = false;
        publishJwks(RSA_KEY_A);
        TenantScopedExecution.runAsAdmin(() -> {
            Lsp lspA = lspRepository.save(new Lsp("T-A", "Test LSP A", LspStatus.ACTIVE));
            Lsp lspB = lspRepository.save(new Lsp("T-B", "Test LSP B", LspStatus.ACTIVE));
            lspAId = lspA.getId();
            lspBId = lspB.getId();
            ApiClientManagementService.CreatedApiClient created = apiClientManagementService.createClient(
                    "Test Mapped Client", null, lspAId, ApiClientStatus.ACTIVE, "t02.test", null);
            mappedClientId = created.client().getId();
            SecurityProperties.EntraMachineIdentity.Mapping mapping =
                    new SecurityProperties.EntraMachineIdentity.Mapping();
            mapping.setExternalTenantId(TENANT);
            mapping.setExternalClientId(APP_CLIENT);
            mapping.setLocalApiClientId(mappedClientId);
            mapping.setEnabled(true);
            securityProperties.getEntraMachineIdentity().setMappings(List.of(mapping));
        });
    }

    @Test
    void mappedEntraTokenCannotReadOrWriteOtherLspData() throws Exception {
        ProductFixture productB = createProduct();
        mapProductToLsp(productB.id(), lspBId);
        JsonNode lspBApplication = createInternalApplication(
                lspBId, productB.id(), "T-B-EXT-001", "ABCDE1234F");

        String token = signEntraToken(builder -> builder.claim("lspId", lspBId.toString()), RSA_KEY_A);
        jwtDecoder.decode(token);

        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", lspBApplication.get("id").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/lsp/loan-applications/external/{externalLoanId}", "T-B-EXT-001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", lspBApplication.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DUPLICATE_APPLICATION\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", lspBApplication.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(lspBApplication.get("status").asText()));
    }

    @Test
    void mappedEntraAppUsesApiAllowlistDespiteExternalRoleNamesAndForgedLspClaim() throws Exception {
        TenantScopedExecution.runAsAdmin(() -> {
            Lsp lsp = lspRepository.findById(lspAId).orElseThrow();
            apiAllowlistRepository.save(new com.bhawana.lms.domain.LspIpAllowlistEntry(
                    lsp, "192.0.2.0/24", "approved machine range"));
            lsp.updateAllowlistEnforcement(false, true);
            lspRepository.save(lsp);
            ipAllowlistFilter.invalidateCache(lspAId, null);
        });
        String token = signEntraToken(builder -> builder.claim("lspId", lspBId.toString()), RSA_KEY_A);
        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + token)
                        .with(com.bhawana.lms.support.IpTestSupport.remoteAddr("198.51.100.99")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IP_NOT_ALLOWED"));
        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + token)
                        .with(com.bhawana.lms.support.IpTestSupport.remoteAddr("192.0.2.25")))
                .andExpect(status().isOk());
    }

    @Test
    void expiredEntraTokenIsRejected() {
        String token = signEntraToken(
                builder -> builder.expirationTime(Date.from(Instant.now().minusSeconds(60))),
                RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void nonRs256EntraShapedTokenIsRejected() {
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject(APP_CLIENT)
                        .audience(List.of(API_AUDIENCE))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .claim("tid", TENANT)
                        .claim("azp", APP_CLIENT)
                        .claim("idtyp", EntraMachineJwtValidator.IDTYP_APP)
                        .claim("roles", List.of(APP_ROLE))
                        .claim("lspId", lspAId.toString())
                        .build())).getTokenValue();
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void humanHs256TokenCannotReachMachineSurface() throws Exception {
        String humanToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(securityProperties.getJwt().getIssuer())
                        .subject("t02.human")
                        .audience(List.of(securityProperties.getJwt().getHumanAudience()))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .claim("roles", List.of("OPS_USER"))
                        .claim(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                                ManagedUserJwtPrincipalResolver.AUTH_TYPE_HUMAN)
                        .build())).getTokenValue();

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + humanToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void entraTokenCannotUseHumanPasswordOrAdminContext() throws Exception {
        String token = signEntraToken(Function.identity(), RSA_KEY_A);

        ObjectNode passwordBody = objectMapper.createObjectNode();
        passwordBody.put("newPassword", "EntraCannot123!");
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody.toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/internal/system/bootstrap-sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void credentialsInvalidatedAfterTokenIatRejectsEntraToken() {
        String token = signEntraToken(Function.identity(), RSA_KEY_A);
        jwtDecoder.decode(token);

        TenantScopedExecution.runAsAdmin(() -> {
            ApiClient client = apiClientRepository.findById(mappedClientId).orElseThrow();
            client.deactivate();
            apiClientRepository.save(client);
        });

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void duplicateExternalMappingFailsClosed() {
        UUID secondClientId = TenantScopedExecution.callAsAdmin(() -> {
            ApiClientManagementService.CreatedApiClient second = apiClientManagementService.createClient(
                    "Test Second Client", null, lspBId, ApiClientStatus.ACTIVE, "t02.test", null);
            return second.client().getId();
        });
        SecurityProperties.EntraMachineIdentity.Mapping duplicate =
                new SecurityProperties.EntraMachineIdentity.Mapping();
        duplicate.setExternalTenantId(TENANT);
        duplicate.setExternalClientId(APP_CLIENT);
        duplicate.setLocalApiClientId(secondClientId);
        duplicate.setEnabled(true);
        securityProperties.getEntraMachineIdentity().setMappings(List.of(
                securityProperties.getEntraMachineIdentity().getMappings().get(0),
                duplicate));

        String token = signEntraToken(Function.identity(), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void unknownKidIsRejectedWithoutHammeringJwks() throws Exception {
        jwtDecoder.decode(signEntraToken(Function.identity(), RSA_KEY_A));

        RSAKey unknownKidKey = new RSAKeyGenerator(2048).keyID("kid-unknown").generate();
        String unknownKidToken = signEntraToken(Function.identity(), unknownKidKey);
        int hitsBeforeUnknownKid = JWKS_HTTP_HITS.get();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(Exception.class, () -> jwtDecoder.decode(unknownKidToken));
        }
        assertTrue(JWKS_HTTP_HITS.get() - hitsBeforeUnknownKid <= 2,
                () -> "expected bounded JWKS refresh, got " + (JWKS_HTTP_HITS.get() - hitsBeforeUnknownKid) + " HTTP hits");
    }

    @Test
    void jwksOutageAfterCacheExpiryFailsClosed() throws Exception {
        // Outage retry state belongs to this decoder and must not leak into other test cases.
        JwtDecoder outageDecoder = TrustedJwksJwtDecoderFactory.build(
                securityProperties.getEntraMachineIdentity(), entraMachineJwtValidator);
        String token = signEntraToken(Function.identity(), RSA_KEY_A);
        outageDecoder.decode(token);

        Thread.sleep(1_100L);
        publishJwks(RSA_KEY_B);
        jwksOutage = true;
        int hitsBeforeOutage = JWKS_HTTP_HITS.get();
        assertThrows(Exception.class, () -> outageDecoder.decode(token));
        assertTrue(JWKS_HTTP_HITS.get() > hitsBeforeOutage);
    }

    @Test
    void wrongTenantIsRejected() {
        String token = signEntraToken(
                builder -> builder.claim("tid", "99999999-9999-9999-9999-999999999999"), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void wrongIssuerIsRejected() {
        String token = signEntraToken(
                builder -> builder.issuer("https://evil.example.com/" + TENANT + "/v2.0"), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void wrongAudienceIsRejected() {
        String token = signEntraToken(builder -> builder.audience("other-audience"), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void delegatedScpTokenIsRejected() {
        String token = signEntraToken(builder -> builder.claim("scp", "User.Read"), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void missingAppIdentityIsRejected() {
        String token = signEntraToken(builder -> builder.claim("idtyp", "user"), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void missingAppRoleIsRejected() {
        String token = signEntraToken(builder -> builder.claim("roles", List.of("Other.Role")), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void unmappedClientIsRejected() {
        String token = signEntraToken(
                builder -> builder.claim("azp", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void disabledMappingIsRejected() {
        securityProperties.getEntraMachineIdentity().getMappings().get(0).setEnabled(false);
        String token = signEntraToken(Function.identity(), RSA_KEY_A);
        assertThrows(Exception.class, () -> jwtDecoder.decode(token));
    }

    @Test
    void revokedMappingRejectsTokensIssuedBeforeAndAfterRevocation() {
        Instant revokedAt = Instant.now().minusSeconds(5);
        String oldToken = signEntraToken(
                builder -> builder.issueTime(Date.from(revokedAt.minusSeconds(5))), RSA_KEY_A);
        String newToken = signEntraToken(
                builder -> builder.issueTime(Date.from(revokedAt.plusSeconds(1))), RSA_KEY_A);
        jwtDecoder.decode(oldToken);
        jwtDecoder.decode(newToken);
        var mapping = securityProperties.getEntraMachineIdentity().getMappings().get(0);
        mapping.setRevokedAt(revokedAt);
        assertThrows(Exception.class, () -> jwtDecoder.decode(oldToken));
        assertThrows(Exception.class, () -> jwtDecoder.decode(newToken));
        mapping.setRevokedAt(null);
        jwtDecoder.decode(newToken);
    }

    @Test
    void keyRolloverAcceptsNewKid() throws Exception {
        String oldToken = signEntraToken(Function.identity(), RSA_KEY_A);
        jwtDecoder.decode(oldToken);
        publishJwks(RSA_KEY_A, RSA_KEY_B);
        Thread.sleep(2_100L);
        String newToken = signEntraToken(builder -> builder.jwtID(UUID.randomUUID().toString()), RSA_KEY_B);
        jwtDecoder.decode(newToken);
    }

    @Test
    void entraDisabledByDefaultInBaseConfiguration() {
        SecurityProperties.EntraMachineIdentity fresh = new SecurityProperties.EntraMachineIdentity();
        assertTrue(!fresh.isEnabled());
    }

    private static void publishJwks(RSAKey... keys) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode set = mapper.createObjectNode();
            var array = mapper.createArrayNode();
            for (RSAKey key : keys) {
                array.add(mapper.readTree(key.toPublicJWK().toJSONString()));
            }
            set.set("keys", array);
            jwksJson = mapper.writeValueAsString(set);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String signEntraToken(
            Function<JWTClaimsSet.Builder, JWTClaimsSet.Builder> customizer,
            RSAKey signingKey
    ) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(APP_CLIENT)
                    .audience(API_AUDIENCE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(600)))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("tid", TENANT)
                    .claim("azp", APP_CLIENT)
                    .claim("idtyp", EntraMachineJwtValidator.IDTYP_APP)
                    .claim("roles", List.of(APP_ROLE));
            JWTClaimsSet claims = customizer.apply(builder).build();
            SignedJWT signed = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims);
            signed.sign(new RSASSASigner(signingKey.toPrivateKey()));
            return signed.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ProductFixture createProduct() throws Exception {
        String code = "TSTP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Test Product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("2500000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 36,
                                "status", "ACTIVE"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new ProductFixture(json.get("id").asText());
    }

    private void mapProductToLsp(String productId, UUID lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId.toString())))))
                .andExpect(status().isOk());
    }

    private JsonNode createInternalApplication(
            UUID lspId,
            String productId,
            String externalLoanId,
            String borrowerPan
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId, productId, externalLoanId, borrowerPan))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private LinkedHashMap<String, Object> loanApplicationPayload(
            UUID lspId,
            String productId,
            String externalLoanId,
            String borrowerPan
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId.toString());
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Test Borrower");
        payload.put("borrowerMobile", "9999900001");
        payload.put("borrowerEmail", externalLoanId.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1992, 3, 10));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("78000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);
        return payload;
    }

    private LinkedHashMap<String, Object> defaultLoanPayload(UUID lspId, String productId, String externalLoanId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId.toString());
        payload.put("productId", productId);
        payload.put("lspLoanId", externalLoanId);
        payload.put("fullName", "Test Borrower");
        payload.put("emailAddress", externalLoanId.toLowerCase() + "@example.com");
        payload.put("mobileNumber", "9999900001");
        payload.put("dob", "1992-03-10");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Test Parent");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", "ABCDE1234F");
        payload.put("loanAmount", new BigDecimal("45000.00"));
        payload.put("interestRate", new BigDecimal("18.50"));
        payload.put("loanTenure", 12);
        payload.put("addressLine1", "Test Street");
        payload.put("addressCity", "Mumbai");
        payload.put("addressState", "Maharashtra");
        payload.put("addressZipcode", "400001");
        payload.put("employmentStatus", "SALARIED");
        payload.put("organizationName", "Test Corp");
        payload.put("empId", "EMP-TEST");
        payload.put("employmentCity", "Mumbai");
        payload.put("employmentState", "Maharashtra");
        payload.put("employmentZip", "400001");
        payload.put("monthlyIncome", new BigDecimal("78000.00"));
        payload.put("annualIncome", new BigDecimal("936000.00"));
        payload.put("bankAccountNumber", "123456789012");
        payload.put("bankName", "Demo Bank");
        payload.put("ifscCode", "HDFC0001234");
        payload.put("accountHolderName", "Test Borrower");
        payload.put("referencePersonName", "Test Ref");
        payload.put("referencePersonNumber", "9888877777");
        return payload;
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private record ProductFixture(String id) {
    }

}
