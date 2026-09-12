package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.service.AuthAuthenticationService;
import com.bhawana.lms.service.HumanLoginLockoutService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import org.springframework.security.authentication.BadCredentialsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Immediate human login lockout must serialize failure counting on {@code FOR UPDATE}
 * across independent PostgreSQL connections.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class HumanLoginLockoutPostgresTest {

    private static final String CLIENT_IP = "198.51.100.42";

    @Autowired private com.bhawana.lms.repo.LspRepository lspRepository;
    @Autowired private com.bhawana.lms.repo.LspUiIpAllowlistRepository uiAllowlistRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private com.bhawana.lms.repo.AppRoleRepository appRoleRepository;
    @Autowired private AuthEventAuditRepository authEventAuditRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private OpsAlertRepository opsAlertRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private HumanLoginLockoutService humanLoginLockoutService;
    @Autowired private AuthAuthenticationService authAuthenticationService;

    @BeforeEach
    void setUpUsers() {
        authEventAuditRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        opsAlertRepository.deleteAll();
        appUserRepository.deleteAll();

        AppRole opsUserRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();

        appUserRepository.save(new AppUser(
                "t11.user",
                "t11.user@bhawana.local",
                passwordEncoder.encode("T11Password123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(opsUserRole)
        ));
    }

    @Test
    void concurrent_failed_logins_on_two_connections_reach_threshold_and_lock() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            attemptLogin("t11.user", "WrongPassword!", CLIENT_IP)
                    .andExpect(status().isUnauthorized());
        }

        CountDownLatch insideCounterFence = new CountDownLatch(2);
        CountDownLatch releaseCounterFence = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> backendPids = new ConcurrentLinkedQueue<>();
        humanLoginLockoutService.setPreCounterLockHookForTest(() -> {
            backendPids.add(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            insideCounterFence.countDown();
            awaitLatch(releaseCounterFence);
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                awaitLatch(start);
                assertLoginFails("t11.user@bhawana.local", "WrongPassword!", "198.51.100.50");
                return null;
            }));
            Future<?> second = executor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                awaitLatch(start);
                assertLoginFails("t11.user@bhawana.local", "WrongPassword!", "198.51.100.51");
                return null;
            }));
            awaitLatch(ready);
            start.countDown();
            awaitLatch(insideCounterFence);
            assertEquals(2, backendPids.size());
            assertEquals(2, new HashSet<>(backendPids).size(),
                    "overlapping counter fences must use distinct PostgreSQL backends");
            releaseCounterFence.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            humanLoginLockoutService.setPreCounterLockHookForTest(null);
        }

        entityManager.clear();
        AppUser locked = appUserRepository.findByUsername("t11.user").orElseThrow();
        assertNotNull(locked.getLockedAt());
        assertEquals(AppUser.LOCK_REASON_BRUTE_FORCE, locked.getLockReason());
        assertEquals(5, locked.getFailedLoginAttempts(),
                "serialized 3→4→5 overlap must reach threshold; lost update would leave 4 unlocked");
    }

    @Test
    void disabledAccountRejectionsDoNotCountAsWrongPasswords() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET status = 'INACTIVE' WHERE username = 't11.user'");
        for (int attempt = 0; attempt < 5; attempt++) {
            attemptLogin("t11.user", "T11Password123!", CLIENT_IP)
                    .andExpect(status().isUnauthorized());
        }
        AppUser user = appUserRepository.findByUsername("t11.user").orElseThrow();
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getLockedAt());
        assertEquals(5, authEventAuditRepository.findAll().stream()
                .filter(event -> event.getFailureReason() ==
                        com.bhawana.lms.domain.AuthEventFailureReason.ACCOUNT_DISABLED).count());
        jdbcTemplate.update("UPDATE app_user SET status = 'ACTIVE' WHERE username = 't11.user'");
        attemptLogin("t11.user", "T11Password123!", CLIENT_IP).andExpect(status().isOk());
    }

    @Test
    void elapsedWindowRestartsAtOneWithoutScheduler() throws Exception {
        jdbcTemplate.update("""
                UPDATE app_user SET failed_login_attempts = 4,
                    failed_login_window_started_at = now() - interval '1 day'
                WHERE username = 't11.user'
                """);
        attemptLogin("t11.user", "WrongPassword!", CLIENT_IP).andExpect(status().isUnauthorized());
        AppUser user = appUserRepository.findByUsername("t11.user").orElseThrow();
        assertEquals(1, user.getFailedLoginAttempts());
        assertNull(user.getLockedAt());
        assertTrue(user.getFailedLoginWindowStartedAt().isAfter(java.time.Instant.now().minusSeconds(60)));
    }

    @Test
    void successfulLoginClearsFailureWindow() throws Exception {
        attemptLogin("t11.user", "WrongPassword!", CLIENT_IP).andExpect(status().isUnauthorized());
        attemptLogin("t11.user", "T11Password123!", CLIENT_IP).andExpect(status().isOk());
        AppUser user = appUserRepository.findByUsername("t11.user").orElseThrow();
        assertEquals(0, user.getFailedLoginAttempts());
        assertNull(user.getFailedLoginWindowStartedAt());
    }

    @Test
    void unknownUserHasSamePublicRejectionWithoutCreatingCounter() throws Exception {
        long usersBefore = appUserRepository.count();
        String known = attemptLogin("t11.user", "WrongPassword!", CLIENT_IP)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = attemptLogin("t11.unknown", "WrongPassword!", CLIENT_IP)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(objectMapper.readTree(known).get("code"), objectMapper.readTree(unknown).get("code"));
        assertEquals(objectMapper.readTree(known).get("message"), objectMapper.readTree(unknown).get("message"));
        assertEquals(usersBefore, appUserRepository.count());
    }

    @Test
    void ipPolicyRejectionDoesNotConsumeCredentialFailureBudget() throws Exception {
        var lsp = lspRepository.save(new com.bhawana.lms.domain.Lsp(
                "T-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                "Test allowlist", com.bhawana.lms.domain.LspStatus.ACTIVE));
        uiAllowlistRepository.save(new com.bhawana.lms.domain.LspUiIpAllowlistEntry(
                lsp, "192.0.2.0/24", "permitted test range"));
        lsp.updateAllowlistEnforcement(true, false);
        lspRepository.save(lsp);
        AppRole role = appRoleRepository.findByCodeIn(List.of(RoleCode.LSP_UI_READ)).getFirst();
        var user = appUserRepository.save(new AppUser("t11.ip", "t11.ip@bhawana.local",
                passwordEncoder.encode("T11Password123!"), UserStatus.ACTIVE, lsp, Set.of(role)));
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                attemptLogin("t11.ip", "T11Password123!", CLIENT_IP)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error").value("LOGIN_IP_NOT_ALLOWED"));
            }
            AppUser after = appUserRepository.findById(user.getId()).orElseThrow();
            assertEquals(0, after.getFailedLoginAttempts());
            assertNull(after.getLockedAt());
            attemptLogin("t11.ip", "T11Password123!", "192.0.2.25").andExpect(status().isOk());
        } finally {
            refreshTokenRepository.deleteAll();
            appUserRepository.deleteById(user.getId());
            uiAllowlistRepository.deleteAll(uiAllowlistRepository.findAll().stream()
                    .filter(entry -> entry.getLsp().getId().equals(lsp.getId())).toList());
            lspRepository.deleteById(lsp.getId());
        }
    }

    private void assertLoginFails(String email, String password, String clientIp) {
        try {
            authAuthenticationService.login(email, password, clientIp);
            throw new AssertionError("expected failed login");
        } catch (BadCredentialsException expected) {
            // expected
        }
    }

    private ResultActions attemptLogin(String username, String password, String clientIp) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", username + "@bhawana.local");
        body.put("password", password);
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(com.bhawana.lms.support.IpTestSupport.remoteAddr(clientIp))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting latch", exception);
        }
    }
}
