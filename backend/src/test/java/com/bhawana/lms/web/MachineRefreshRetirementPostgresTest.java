package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.service.AuthAuthenticationService;
import com.bhawana.lms.service.AuthTokenService;
import com.bhawana.lms.service.HumanSessionService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.bhawana.lms.web.AuthApiResponses.ClientCredentialsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Machine refresh retirement, legacy row rejection, fenced client-credentials mint and
 * preserved human refresh.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class MachineRefreshRetirementPostgresTest {

    private static final String ACTOR_IP = "127.0.0.1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LspRepository lspRepository;
    @Autowired private ApiClientManagementService apiClientManagementService;
    @Autowired private ApiClientRepository apiClientRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private AppRoleRepository appRoleRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private AuthAuthenticationService authAuthenticationService;
    @Autowired private HumanSessionService humanSessionService;
    @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        humanSessionService.setPreIssuanceHookForTest(null);
    }

    @Test
    void tokenEndpointReturnsAccessOnlyWithoutRefreshCookie() throws Exception {
        ApiClientManagementService.CreatedApiClient created = createClient();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ClientCredentialsRequest(
                                created.client().getClientId(), created.rawSecret()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        assertNull(result.getResponse().getCookie("lms-refresh"));
        assertNull(result.getResponse().getHeader("Set-Cookie"));
    }

    @Test
    void legacyMachineRefreshRejectedBeforeAccessMint() throws Exception {
        ApiClientManagementService.CreatedApiClient created = createClient();
        String rawRefresh = "t20-legacy-machine-refresh-token";
        String tokenHash = sha256Hex(rawRefresh);
        TenantScopedExecution.runAsAdmin(() -> refreshTokenRepository.save(new RefreshToken(
                tokenHash, created.client(), Instant.now().plusSeconds(3600))));

        AuthTokenService.RefreshOutcome outcome = TenantScopedExecution.callAsAdmin(
                () -> authAuthenticationService.refreshSession(rawRefresh, ACTOR_IP, "t20-legacy"));
        assertFalse(outcome.success());
        assertEquals(AuthEventFailureReason.TOKEN_REVOKED, outcome.failureReason());
        assertNull(outcome.tokenResponse());
        assertNull(outcome.newRawRefreshToken());

        TenantScopedExecution.runAsAdmin(() -> {
            RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
            assertTrue(stored.isRevoked());
        });

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("lms-refresh", rawRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void legacyMachineRefreshStillRejectedAfterSecretRotation() throws Exception {
        ApiClientManagementService.CreatedApiClient created = createClient();
        String rawRefresh = "t20-legacy-after-rotate";
        TenantScopedExecution.runAsAdmin(() -> refreshTokenRepository.save(new RefreshToken(
                sha256Hex(rawRefresh), created.client(), Instant.now().plusSeconds(3600))));

        TenantScopedExecution.runAsAdmin(() -> apiClientManagementService.rotateSecret(
                created.client().getId(), "t20.admin", ACTOR_IP, 300));

        AuthTokenService.RefreshOutcome outcome = TenantScopedExecution.callAsAdmin(
                () -> authAuthenticationService.refreshSession(rawRefresh, ACTOR_IP, "t20-rotate"));
        assertFalse(outcome.success());
        assertEquals(AuthEventFailureReason.TOKEN_REVOKED, outcome.failureReason());
    }

    @Test
    void humanRefreshStillSucceedsAfterMachineRetirement() throws Exception {
        TenantScopedExecution.runAsAdmin(() -> {
            if (appUserRepository.findByUsername("t20.human").isEmpty()) {
                AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                        .findFirst().orElseThrow();
                appUserRepository.save(new AppUser(
                        "t20.human",
                        "t20.human@bhawana.local",
                        passwordEncoder.encode("T20HumanPassword123!"),
                        UserStatus.ACTIVE,
                        null,
                        Set.of(opsRole)));
            }
        });
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", "t20.human@bhawana.local");
        body.put("password", "T20HumanPassword123!");
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = login.getResponse().getCookie("lms-refresh");
        assertNotNull(cookie);
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void secretRotationCommittedDuringClientCredentialsGapIssuesNoToken() throws Exception {
        ApiClientManagementService.CreatedApiClient created = createClient();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        humanSessionService.setPreIssuanceHookForTest(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<?> fenced = executor.submit(() -> TenantScopedExecution.runAsAdmin(() -> {
                try {
                    authAuthenticationService.issueClientCredentialsToken(
                            created.client().getClientId(), created.rawSecret(), ACTOR_IP);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }));
            awaitLatch(entered);
            TenantScopedExecution.runAsAdmin(() -> apiClientManagementService.rotateSecret(
                    created.client().getId(), "t20.admin", ACTOR_IP, 0));
            release.countDown();
            Exception failure = assertThrows(Exception.class, () -> fenced.get(20, TimeUnit.SECONDS));
            assertNotNull(failure);
            assertTrue(hasCause(failure, BadCredentialsException.class));
            String rotatedSecret = TenantScopedExecution.callAsAdmin(() -> {
                ApiClient client = apiClientRepository.findById(created.client().getId()).orElseThrow();
                assertEquals(ApiClientStatus.ACTIVE, client.getStatus());
                return apiClientManagementService.rotateSecret(
                        created.client().getId(), "t20.admin", ACTOR_IP, 300).rawSecret();
            });
            mockMvc.perform(post("/api/v1/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ClientCredentialsRequest(
                                    created.client().getClientId(), rotatedSecret))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isString());
        } finally {
            humanSessionService.setPreIssuanceHookForTest(null);
        }
    }

    @Test
    void rotationDuringCredentialVerificationCannotBeOverwrittenByStaleUsageUpdate() throws Exception {
        ApiClientManagementService.CreatedApiClient created = createClient();
        CountDownLatch verifyingOldSecret = new CountDownLatch(1);
        CountDownLatch rotationCommitted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean pauseOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            boolean result = (boolean) invocation.callRealMethod();
            if (created.rawSecret().contentEquals(invocation.getArgument(0, CharSequence.class))
                    && pauseOnce.compareAndSet(true, false)) {
                verifyingOldSecret.countDown();
                awaitLatch(rotationCommitted);
            }
            return result;
        }).when(passwordEncoder).matches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> oldLogin = executor.submit(() -> TenantScopedExecution.runAsAdmin(() ->
                    authAuthenticationService.issueClientCredentialsToken(
                            created.client().getClientId(), created.rawSecret(), ACTOR_IP)));
            awaitLatch(verifyingOldSecret);
            ApiClientManagementService.RotatedApiClient rotated = TenantScopedExecution.callAsAdmin(() ->
                    apiClientManagementService.rotateSecret(created.client().getId(), "t20.admin", ACTOR_IP, 0));
            rotationCommitted.countDown();
            Exception failure = assertThrows(Exception.class, () -> oldLogin.get(20, TimeUnit.SECONDS));
            assertTrue(hasCause(failure, BadCredentialsException.class));
            ApiClient current = apiClientRepository.findById(created.client().getId()).orElseThrow();
            assertTrue(passwordEncoder.matches(rotated.rawSecret(), current.getSecretHash()));
            assertFalse(passwordEncoder.matches(created.rawSecret(), current.getSecretHash()));
            assertNotNull(current.getCredentialsInvalidatedAt());
        } finally {
            rotationCommitted.countDown();
            executor.shutdownNow();
            org.mockito.Mockito.reset(passwordEncoder);
        }
    }

    @Test
    void disableCommittedDuringClientCredentialsGapIssuesNoToken() throws Exception {
        ApiClientManagementService.CreatedApiClient created = createClient();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        humanSessionService.setPreIssuanceHookForTest(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<?> fenced = executor.submit(() -> TenantScopedExecution.runAsAdmin(() -> {
                try {
                    authAuthenticationService.issueClientCredentialsToken(
                            created.client().getClientId(), created.rawSecret(), ACTOR_IP);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }));
            awaitLatch(entered);
            TenantScopedExecution.runAsAdmin(() -> apiClientManagementService.updateClient(
                    created.client().getId(),
                    "t20.admin",
                    ACTOR_IP,
                    null,
                    null,
                    ApiClientStatus.INACTIVE));
            release.countDown();
            Exception failure = assertThrows(Exception.class, () -> fenced.get(20, TimeUnit.SECONDS));
            assertNotNull(failure);
            assertTrue(hasCause(failure, BadCredentialsException.class));
            TenantScopedExecution.runAsAdmin(() -> {
                ApiClient client = apiClientRepository.findById(created.client().getId()).orElseThrow();
                assertEquals(ApiClientStatus.INACTIVE, client.getStatus());
            });
            mockMvc.perform(post("/api/v1/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ClientCredentialsRequest(
                                    created.client().getClientId(), created.rawSecret()))))
                    .andExpect(status().isUnauthorized());
        } finally {
            humanSessionService.setPreIssuanceHookForTest(null);
        }
    }

    private ApiClientManagementService.CreatedApiClient createClient() {
        return TenantScopedExecution.callAsAdmin(() -> {
            Lsp lsp = lspRepository.save(new Lsp("T-" + java.util.UUID.randomUUID(), "Test LSP", LspStatus.ACTIVE));
            return apiClientManagementService.createClient(
                    "Test Client", null, lsp.getId(), ApiClientStatus.ACTIVE, "t20.test", null);
        });
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expected) {
        Throwable current = throwable;
        while (current != null) {
            if (expected.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
