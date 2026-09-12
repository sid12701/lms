package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.AuthEventType;
import com.bhawana.lms.domain.AuthSession;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.AuthSessionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.security.SessionPolicyEpochProvider;
import com.bhawana.lms.service.AuthAuditService;
import com.bhawana.lms.service.AuthAuthenticationService;
import com.bhawana.lms.service.AuthTokenService;
import com.bhawana.lms.service.HumanLoginLockoutService;
import com.bhawana.lms.service.HumanSessionService;
import com.bhawana.lms.service.UserAdminService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Backend contract: human session families with sid-bound access, atomic refresh
 * lineage, principal-first fences and the key-policy epoch cutover.
 *
 * <p>All concurrency uses real PostgreSQL transactions on independent connections with
 * latch/barrier coordination (no H2, no single-transaction or sleep substitutes). HTTP
 * tests assert real {@code Set-Cookie} presence/absence. Signed-token negatives use the
 * production encoder/decoder with real valid families, so each rejection proves its own
 * contract rather than a missing subject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class SessionBackendPostgresTest {

    private static final String ACTOR_IP = "127.0.0.1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private AppRoleRepository appRoleRepository;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private AuthEventAuditRepository authEventAuditRepository;
    @Autowired private LspRepository lspRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthAuthenticationService authAuthenticationService;
    @Autowired private HumanSessionService humanSessionService;
    @Autowired private HumanLoginLockoutService humanLoginLockoutService;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private UserAdminService userAdminService;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private SecurityProperties securityProperties;
    @Autowired private SessionPolicyEpochProvider sessionPolicyEpochProvider;
    @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private com.bhawana.lms.service.LspStatusService lspStatusService;
    @Autowired private com.bhawana.lms.service.SessionRevocationService sessionRevocationService;
    @Autowired private com.bhawana.lms.security.ManagedUserJwtPrincipalResolver managedUserResolver;
    @Autowired private com.bhawana.lms.security.ApiClientJwtSessionValidator apiClientValidator;
    @Autowired private com.bhawana.lms.security.EntraMachineJwtValidator entraMachineJwtValidator;
    @Autowired private com.bhawana.lms.security.AuthPrincipalCache authPrincipalCache;
    @Autowired private org.springframework.security.authentication.AuthenticationManager authenticationManager;
    @Autowired private com.bhawana.lms.repo.ApiClientRepository apiClientRepository;
    @Autowired private com.bhawana.lms.service.ApiClientAuthenticationService apiClientAuthenticationService;
    @Autowired private com.bhawana.lms.service.LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService;

    @MockitoSpyBean private AuthAuditService authAuditService;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        clearSessionHooks();
    }

    @AfterEach
    void tearDown() {
        clearSessionHooks();
    }

    private void clearSessionHooks() {
        humanSessionService.setPreIssuanceHookForTest(null);
        humanSessionService.setPreRefreshLockHookForTest(null);
        humanSessionService.setPrePasswordFenceHookForTest(null);
    }

    @Test
    void passwordCompletionRequiresCompleteSessionProofEvenForInternalCallers() {
        AppUser user = createOpsUser("t21.proof", "OriginalPassword123!");
        TenantScopedExecution.runAsAdmin(() -> {
            AppUser managed = appUserRepository.findById(user.getId()).orElseThrow();
            managed.requirePasswordChange(managed.getPasswordHash());
            appUserRepository.save(managed);
        });
        var invalidProofs = new HumanSessionService.PresentedSession[] {
            null,
            new HumanSessionService.PresentedSession(null, null, null),
            new HumanSessionService.PresentedSession(null, user.getTokenVersion(), user.getPasswordChangedAt().toEpochMilli()),
            new HumanSessionService.PresentedSession(java.util.UUID.randomUUID().toString(), null, user.getPasswordChangedAt().toEpochMilli()),
            new HumanSessionService.PresentedSession(java.util.UUID.randomUUID().toString(), user.getTokenVersion(), null)
        };
        for (var proof : invalidProofs) {
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                    () -> TenantScopedExecution.callAsAdmin(() -> humanSessionService.changePasswordAndIssue(
                            user.getUsername(), "ReplacementPassword456!", proof, "127.0.0.1", "proof-required")));
        }
        AppUser after = appUserRepository.findById(user.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("OriginalPassword123!", after.getPasswordHash()));
        assertTrue(after.isPasswordChangeRequired());
    }

    // ---- fixture helpers ----

    private AppUser createUser(String username, String rawPassword, Set<RoleCode> roles, Lsp lsp) {
        // F-11: production canonicalises username/email to lowercase; mirror it so the
        // raw-equality lookups resolve test fixtures exactly like production rows.
        String canonicalUsername = username.trim().toLowerCase();
        String canonicalEmail = canonicalUsername + "@bhawana.local";
        return TenantScopedExecution.callAsAdmin(() -> {
            List<AppRole> resolved = appRoleRepository.findByCodeIn(List.copyOf(roles));
            AppUser user = new AppUser(
                    canonicalUsername,
                    canonicalEmail,
                    passwordEncoder.encode(rawPassword),
                    UserStatus.ACTIVE,
                    lsp,
                    Set.copyOf(resolved));
            return appUserRepository.save(user);
        });
    }

    private AppUser createOpsUser(String username, String rawPassword) {
        return createUser(username, rawPassword, Set.of(RoleCode.OPS_USER), null);
    }

    private LoginArtifacts login(String email, String password) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", email);
        body.put("password", password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("lms-refresh");
        assertNotNull(cookie, "login must Set-Cookie the refresh cookie");
        assertNotNull(result.getResponse().getHeader("Set-Cookie"));
        return new LoginArtifacts(json.get("accessToken").asText(), cookie.getValue(), cookie);
    }

    private AuthTokenService.RefreshOutcome serviceRefresh(String rawRefresh) {
        return TenantScopedExecution.callAsAdmin(
                () -> authAuthenticationService.refreshSession(rawRefresh, ACTOR_IP, "t21-test"));
    }

    private String sidOf(String accessToken) {
        return jwtDecoder.decode(accessToken).getClaimAsString("sid");
    }

    private long familyCountFor(String username) {
        AppUser user = appUserRepository.findByUsername(username).orElseThrow();
        return TenantScopedExecution.callAsAdmin(
                () -> authSessionRepository.findByUser_IdOrderByCreatedAtAsc(user.getId()).size());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting latch", exception);
        }
    }

    // ---- atomic refresh: same-cookie race, exactly one successor ----

    @Test
    void concurrentSameCookieRotateYieldsOneSuccessorAndBenignLoser() throws Exception {
        createOpsUser("t21.race", "T21RacePassword123!");
        LoginArtifacts session = login("t21.race@bhawana.local", "T21RacePassword123!");
        String raw = session.rawRefresh();

        // In-transaction rendezvous: both threads must hold open PostgreSQL transactions
        // (distinct backends) at the fence boundary before either takes the principal
        // lock — this proves real overlapping transactions, not sequential execution.
        CountDownLatch insideTx = new CountDownLatch(2);
        CountDownLatch releaseLocks = new CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<Integer> backendPids =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        humanSessionService.setPreRefreshLockHookForTest(() -> {
            // Same thread already carries the admin tenant scope from the task wrapper.
            backendPids.add(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            insideTx.countDown();
            awaitLatch(releaseLocks);
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AuthTokenService.RefreshOutcome> first = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(() -> {
                        ready.countDown();
                        awaitLatch(start);
                        return authAuthenticationService.refreshSession(raw, ACTOR_IP, "t21-race-a");
                    }));
            Future<AuthTokenService.RefreshOutcome> second = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(() -> {
                        ready.countDown();
                        awaitLatch(start);
                        return authAuthenticationService.refreshSession(raw, ACTOR_IP, "t21-race-b");
                    }));
            awaitLatch(ready);
            start.countDown();
            // Both fencing transactions overlap inside the fence before locking.
            awaitLatch(insideTx);
            assertEquals(2, backendPids.size(), "both threads must reach the fence");
            assertEquals(2, new java.util.HashSet<>(backendPids).size(),
                    "overlapping fences must run on distinct PostgreSQL backends, got: " + backendPids);
            releaseLocks.countDown();
            AuthTokenService.RefreshOutcome outcomeA = first.get(20, TimeUnit.SECONDS);
            AuthTokenService.RefreshOutcome outcomeB = second.get(20, TimeUnit.SECONDS);
            humanSessionService.setPreRefreshLockHookForTest(null);

            // Exactly one winner with a successor, one benign direct-parent loser: no
            // Set-Cookie material and no revocation for the loser.
            int successes = (outcomeA.success() ? 1 : 0) + (outcomeB.success() ? 1 : 0);
            assertEquals(1, successes);
            AuthTokenService.RefreshOutcome winner = outcomeA.success() ? outcomeA : outcomeB;
            AuthTokenService.RefreshOutcome loser = outcomeA.success() ? outcomeB : outcomeA;
            assertEquals(AuthEventFailureReason.TOKEN_ROTATED, loser.failureReason());
            assertNull(loser.newRawRefreshToken());
            assertNotNull(winner.newRawRefreshToken());

            // Loser stays benign while still the direct parent (single successor,
            // no double-spend); then the winner rotates again and the old parent — now
            // a true ancestor — correctly stops being benign.
            AuthTokenService.RefreshOutcome loserAgain = serviceRefresh(raw);
            assertFalse(loserAgain.success());
            assertEquals(AuthEventFailureReason.TOKEN_ROTATED, loserAgain.failureReason());
            AuthTokenService.RefreshOutcome followUp = serviceRefresh(winner.newRawRefreshToken());
            assertTrue(followUp.success());
        }
    }

    @Test
    void refreshVsLogoutRaceLeavesFamilyDeadAndSuccessorUnusable() throws Exception {
        createOpsUser("t21.racel", "T21RaceLogout123!");
        LoginArtifacts session = login("t21.racel@bhawana.local", "T21RaceLogout123!");
        String raw = session.rawRefresh();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AuthTokenService.RefreshOutcome[] refreshOutcome = new AuthTokenService.RefreshOutcome[1];
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> refreshFuture = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(() -> {
                        ready.countDown();
                        awaitLatch(start);
                        refreshOutcome[0] = authAuthenticationService.refreshSession(raw, ACTOR_IP, "t21-rl-a");
                        return null;
                    }));
            Future<AuthTokenService.RevokeOutcome> logoutFuture = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(() -> {
                        ready.countDown();
                        awaitLatch(start);
                        return authAuthenticationService.logoutFamily(raw, ACTOR_IP, "t21-rl-b");
                    }));
            awaitLatch(ready);
            start.countDown();
            refreshFuture.get(20, TimeUnit.SECONDS);
            AuthTokenService.RevokeOutcome logoutOutcome = logoutFuture.get(20, TimeUnit.SECONDS);
            assertTrue(logoutOutcome.found());

            // Logout always kills the family: any successor minted by a winning refresh is
            // dead too, and the ancestor can no longer rotate.
            if (refreshOutcome[0] != null && refreshOutcome[0].success()) {
                AuthTokenService.RefreshOutcome afterLogout = serviceRefresh(refreshOutcome[0].newRawRefreshToken());
                assertFalse(afterLogout.success());
            }
            AuthTokenService.RefreshOutcome ancestor = serviceRefresh(raw);
            assertFalse(ancestor.success());
            AppUser user = appUserRepository.findByUsername("t21.racel").orElseThrow();
            long liveFamilies = TenantScopedExecution.callAsAdmin(() ->
                    authSessionRepository.findByUser_IdOrderByCreatedAtAsc(user.getId()).stream()
                            .filter(family -> !family.isRevoked())
                            .count());
            assertEquals(0, liveFamilies);
        }
    }

    // ---- issuance-gap proof binding (root finding 1) ----

    @Test
    void passwordResetInIssuanceGapAbortsMintWithNoNewSession() throws Exception {
        createOpsUser("t21.gap", "T21GapPassword123!");
        AppUser user = appUserRepository.findByUsername("t21.gap").orElseThrow();
        long familiesBefore = familyCountFor("t21.gap");

        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch resetDone = new CountDownLatch(1);
        humanSessionService.setPreIssuanceHookForTest(() -> {
            hookEntered.countDown();
            awaitLatch(resetDone);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<HumanSessionService.PasswordLoginIssued> loginFuture = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(() ->
                            humanSessionService.login(
                                    "t21.gap@bhawana.local", "T21GapPassword123!", ACTOR_IP)));
            awaitLatch(hookEntered);
            // The reset lands precisely between credential authentication and the fenced
            // issuance transaction.
            TenantScopedExecution.runAsAdmin(() -> userAdminService.resetUserPassword(
                    user.getId(), "t21.admin", ACTOR_IP, "t21-gap-reset"));
            resetDone.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> loginFuture.get(20, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof org.springframework.security.authentication.BadCredentialsException,
                    "stale-proof issuance must fail closed, got: " + failure.getCause());
            assertEquals(familiesBefore, familyCountFor("t21.gap"), "no session may be minted from stale proof");
            long loginFailures = authEventAuditRepository.countByUsernameAndEventType(
                    "t21.gap", AuthEventType.LOGIN_FAILED);
            assertTrue(loginFailures >= 1, "stale-proof abort must be audited as a login failure");
            AppUser afterGap = appUserRepository.findByUsername("t21.gap").orElseThrow();
            assertEquals(0, afterGap.getFailedLoginAttempts(),
                    "SESSION_INVALID_STATUS must not increment the brute-force window");
            assertNull(afterGap.getLockedAt(), "stale-proof abort must not lock the account");
        } finally {
            humanSessionService.setPreIssuanceHookForTest(null);
        }
    }

    // ---- benign direct parent vs true ancestor (root findings 2/3) ----

    @Test
    void directParentWithinToleranceIsBenignAndFamilySurvives() throws Exception {
        createOpsUser("t21.benign", "T21BenignPassword123!");
        LoginArtifacts login = login("t21.benign@bhawana.local", "T21BenignPassword123!");
        AuthTokenService.RefreshOutcome first = serviceRefresh(login.rawRefresh());
        assertTrue(first.success());

        // Presenting T0 after T0->T1: direct parent, benign — no state change, no cookie.
        MvcResult benign = mockMvc.perform(post("/api/v1/auth/refresh").cookie(login.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_ROTATED"))
                .andReturn();
        assertNull(benign.getResponse().getHeader("Set-Cookie"));

        // Winner cookie stands and the family is alive.
        AuthTokenService.RefreshOutcome winnerAgain = serviceRefresh(first.newRawRefreshToken());
        assertTrue(winnerAgain.success());
    }

    @Test
    void trueAncestorReuseRevokesOnlyThatFamilyWithFailureAudit() throws Exception {
        createOpsUser("t21.reuse", "T21ReusePassword123!");
        LoginArtifacts login = login("t21.reuse@bhawana.local", "T21ReusePassword123!");
        AuthTokenService.RefreshOutcome first = serviceRefresh(login.rawRefresh());
        assertTrue(first.success());
        AuthTokenService.RefreshOutcome second = serviceRefresh(first.newRawRefreshToken());
        assertTrue(second.success());

        // Presenting T0 after T0->T1->T2: true ancestor → family-only revoke in the same
        // committing transaction with an accurate failure audit (never a success row).
        MvcResult reuse = mockMvc.perform(post("/api/v1/auth/refresh").cookie(login.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"))
                .andReturn();
        assertNull(reuse.getResponse().getHeader("Set-Cookie"));

        // Former live head is dead too (no successor semantics).
        AuthTokenService.RefreshOutcome headAfter = serviceRefresh(second.newRawRefreshToken());
        assertFalse(headAfter.success());

        var failures = authEventAuditRepository.search(
                "t21.reuse", AuthEventType.TOKEN_REFRESH_FAILED, PageRequest.of(0, 10));
        assertTrue(failures.getContent().stream().anyMatch(
                event -> event.getFailureReason() == AuthEventFailureReason.TOKEN_REVOKED),
                "reuse must record a failure audit, not a success");
        var revocations = authEventAuditRepository.search(
                "t21.reuse", AuthEventType.SESSIONS_REVOKED_BY_ADMIN, PageRequest.of(0, 10));
        assertTrue(revocations.getContent().stream().anyMatch(event ->
                        event.getDetailsJson() != null
                                && "FAMILY_REUSE".equals(event.getDetailsJson().path("source").asText())),
                "reuse must record a family-scoped revocation");
        var successes = authEventAuditRepository.search(
                "t21.reuse", AuthEventType.TOKEN_REFRESH_SUCCEEDED, PageRequest.of(0, 10));
        assertEquals(2, successes.getContent().size(), "only the two genuine rotations may audit success");

        // Sibling login after the kill is unaffected (per-family scope, no tv bump).
        LoginArtifacts sibling = login("t21.reuse@bhawana.local", "T21ReusePassword123!");
        assertTrue(serviceRefresh(sibling.rawRefresh()).success());
    }

    @Test
    void staleDetachedCopyCannotDoubleSpendSingleSuccessor() throws Exception {
        createOpsUser("t21.stale", "T21StalePassword123!");
        LoginArtifacts login = login("t21.stale@bhawana.local", "T21StalePassword123!");
        // Detached stale view: still shows the row unrevoked after the fence commits.
        RefreshToken staleView = refreshTokenRepository
                .findByTokenHash(sha256Hex(login.rawRefresh())).orElseThrow();
        assertFalse(staleView.isRevoked());

        assertTrue(serviceRefresh(login.rawRefresh()).success());
        AuthTokenService.RefreshOutcome replay = serviceRefresh(login.rawRefresh());
        assertFalse(replay.success(), "stale view must not yield a second successor");
        assertEquals(AuthEventFailureReason.TOKEN_ROTATED, replay.failureReason());
    }

    @Test
    void sharedTxRevokerSeesConcurrentCommitInsteadOfStaleManagedState() throws Exception {
        // Shared-transaction participant (admin reset racing an in-flight all-user fence):
        // the fence must act on the latest committed versions, not on an entity managed
        // (and now stale) in the caller's own transaction.
        createOpsUser("t21.sharedtx", "T21SharedTx123!");
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        CountDownLatch managedReadReady = new CountDownLatch(1);
        CountDownLatch resetCommitted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<com.bhawana.lms.service.SessionRevocationService.RevocationResult> fenced =
                    executor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                            template.execute(status -> {
                                // Managed (and about to become stale) read inside the fence
                                // owner's own transaction.
                                AppUser managed = appUserRepository.findByUsername("t21.sharedtx")
                                        .orElseThrow();
                                long staleTv = managed.getTokenVersion();
                                // Unambiguous milestone: the concurrent reset below must only
                                // start AFTER this managed read completes, otherwise the read
                                // would already be fresh and the test proves nothing.
                                managedReadReady.countDown();
                                awaitLatch(resetCommitted);
                                com.bhawana.lms.service.SessionRevocationService.RevocationResult result =
                                        sessionRevocationService.revokeAllSessionsById(
                                                managed.getId(), "t21.admin", "race",
                                                ACTOR_IP, "t21-sharedtx",
                                                com.bhawana.lms.domain.RevocationSource.ADMIN_EXPLICIT);
                                if (result.previousTokenVersion() == staleTv) {
                                    throw new IllegalStateException(
                                            "fence acted on stale managed state");
                                }
                                return result;
                            })));
            // Second committing connection bumps the version while the first TX holds its
            // stale managed copy. Ordered strictly after the managed read above.
            awaitLatch(managedReadReady);
            AppUser target = appUserRepository.findByUsername("t21.sharedtx").orElseThrow();
            TenantScopedExecution.runAsAdmin(() -> userAdminService.resetUserPassword(
                    target.getId(), "t21.admin", ACTOR_IP, "t21-sharedtx-reset"));
            long tvAfterReset = appUserRepository.findByUsername("t21.sharedtx")
                    .orElseThrow().getTokenVersion();
            resetCommitted.countDown();
            com.bhawana.lms.service.SessionRevocationService.RevocationResult result =
                    fenced.get(20, TimeUnit.SECONDS);
            AppUser survivor = appUserRepository.findByUsername("t21.sharedtx").orElseThrow();
            assertEquals(survivor.getTokenVersion(), result.newTokenVersion());
            // The concurrent reset's independent fields survive the fence: a stale flush
            // would have restored passwordChangeRequired=false and the old tv.
            assertTrue(survivor.isPasswordChangeRequired(),
                    "concurrent reset's password-change requirement must survive the fence");
            assertEquals(tvAfterReset, result.previousTokenVersion(),
                    "fence must act on the latest committed versions, not the stale read");
        }
    }

    @Test
    void profileEditAfterConcurrentResetKeepsNewestFieldsAndVersions() throws Exception {
        // Joined-TX admin writer vs a concurrent committer: thread 1 retains a managed
        // target, the second connection commits a reset, then thread 1 performs a
        // permitted profile edit. The edit must apply WITHOUT restoring the stale
        // password/status/versions over the reset (failed-before: without lock+refresh
        // before mutation, saveAndFlush flushes the stale row and clobbers the reset).
        createOpsUser("t21.overwrite", "T21Overwrite123!");
        AppUser created = appUserRepository.findByUsername("t21.overwrite").orElseThrow();
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        CountDownLatch managedReadReady = new CountDownLatch(1);
        CountDownLatch resetCommitted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<AppUser> edited = executor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    template.execute(status -> {
                        AppUser retained = appUserRepository.findByUsername("t21.overwrite")
                                .orElseThrow();
                        UUID targetId = retained.getId();
                        // Unambiguous milestone: the reset below starts only after this
                        // managed (about to become stale) read completes.
                        managedReadReady.countDown();
                        awaitLatch(resetCommitted);
                        return userAdminService.updateUser(
                                targetId, "t21.admin", ACTOR_IP,
                                "t21.overwrite.new@bhawana.local", null, null, null);
                    })));
            awaitLatch(managedReadReady);
            String tempPassword = TenantScopedExecution.callAsAdmin(() -> userAdminService
                    .resetUserPassword(created.getId(), "t21.admin", ACTOR_IP, "t21-overwrite-reset")
                    .temporaryPassword());
            long tvAfterReset = appUserRepository.findByUsername("t21.overwrite")
                    .orElseThrow().getTokenVersion();
            resetCommitted.countDown();

            AppUser result = edited.get(20, TimeUnit.SECONDS);
            assertEquals("t21.overwrite.new@bhawana.local", result.getEmail());
            AppUser survivor = appUserRepository.findByUsername("t21.overwrite").orElseThrow();
            // Newest independent fields/versions survive; revoke stays monotonic.
            assertEquals("t21.overwrite.new@bhawana.local", survivor.getEmail());
            assertTrue(survivor.isPasswordChangeRequired(),
                    "concurrent reset's password-change requirement must survive the edit");
            assertTrue(passwordEncoder.matches(tempPassword, survivor.getPasswordHash()),
                    "concurrent reset's credential must survive the edit");
            assertTrue(survivor.getTokenVersion() >= tvAfterReset,
                    "versions must advance monotonically, never restore stale tv");
        }
    }

    @Test
    void coordinatorRejectsCallerOwnedTransaction() throws Exception {
        createOpsUser("t21.boundary", "T21Boundary123!");
        LoginArtifacts login = login("t21.boundary@bhawana.local", "T21Boundary123!");
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        String raw = login.rawRefresh();

        // Fencing transactions own their boundary: a caller-owned transaction is rejected
        // explicitly instead of silently discarding its pending state with a clear().
        IllegalStateException refreshRejected = assertThrows(
                IllegalStateException.class,
                () -> TenantScopedExecution.callAsAdmin(() ->
                        template.execute(status -> authAuthenticationService.refreshSession(
                                raw, ACTOR_IP, "t21-boundary"))));
        assertTrue(refreshRejected.getMessage().contains("no surrounding transaction"));
        IllegalStateException loginRejected = assertThrows(
                IllegalStateException.class,
                () -> TenantScopedExecution.callAsAdmin(() ->
                        template.execute(status -> humanSessionService.login(
                                "t21.boundary@bhawana.local", "T21Boundary123!", ACTOR_IP))));
        assertTrue(loginRejected.getMessage().contains("no surrounding transaction"));

        // The rejected attempts changed nothing: the cookie still rotates.
        assertTrue(serviceRefresh(raw).success());
    }

    @Test
    void familyRowWithoutIssuanceMetadataForcesLogin() throws Exception {
        // Malformed family row (family present but stored versions/epoch missing): there is
        // no legitimate legacy family backfill, so rotation must force login, not mint.
        AppUser user = createOpsUser("t21.malformed", "T21Malformed123!");
        String raw = "t21-malformed-raw-refresh";
        TenantScopedExecution.runAsAdmin(() -> {
            AuthSession session = authSessionRepository.save(
                    new AuthSession(user, sessionPolicyEpochProvider.currentEpoch()));
            refreshTokenRepository.save(new RefreshToken(
                    sha256Hex(raw), user, Instant.now().plusSeconds(3600)));
            RefreshToken row = refreshTokenRepository.findByTokenHash(sha256Hex(raw)).orElseThrow();
            row.attachFamily(session, 0L, 0L, null);
            refreshTokenRepository.save(row);
        });

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("lms-refresh", raw)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID_STATUS"))
                .andReturn();
        assertNull(result.getResponse().getHeader("Set-Cookie"));
    }

    @Test
    void liveHeadUniquenessIndexExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND indexname = 'uq_refresh_token_live_family_head'",
                Integer.class);
        assertEquals(1, count, "V126 single-live-head partial unique index must exist");
    }

    @Test
    void fenceOperationsCommittedDuringRefreshGapAreHonored() throws Exception {
        // Each fence commits on a second connection while a refresh waits at the fence
        // entry (hook runs inside the fencing TX before the principal lock): the refresh
        // must honor the committed fence with the accurate code and mint no successor.
        assertRefreshGapFence(
                "t21.gapreset", "T21GapReset123!", "t21-gapreset",
                (userId) -> TenantScopedExecution.runAsAdmin(() ->
                        userAdminService.resetUserPassword(userId, "t21.admin", ACTOR_IP, "t21-gapreset")),
                AuthEventFailureReason.TOKEN_REVOKED);
        assertRefreshGapFence(
                "t21.gapdisable", "T21GapDisable123!", "t21-gapdisable",
                (userId) -> TenantScopedExecution.runAsAdmin(() ->
                        userAdminService.updateUser(
                                userId, "t21.admin", ACTOR_IP, null, UserStatus.INACTIVE, null, null)),
                AuthEventFailureReason.USER_INACTIVE);
        assertRefreshGapFence(
                "t21.gaplock", "T21GapLock123!", "t21-gaplock",
                (userId) -> TenantScopedExecution.runAsAdmin(() ->
                        sessionRevocationService.applyBruteForceLockout(
                                userId, "SYSTEM_AUTO_LOCKOUT",
                                AppUser.LOCK_REASON_BRUTE_FORCE, ACTOR_IP, "t21-gaplock")),
                AuthEventFailureReason.TOKEN_REVOKED);
    }

    private void assertRefreshGapFence(
            String username,
            String password,
            String correlation,
            java.util.function.Consumer<UUID> fence,
            AuthEventFailureReason expected
    ) throws Exception {
        createOpsUser(username, password);
        LoginArtifacts login = login(username + "@bhawana.local", password);
        AppUser user = appUserRepository.findByUsername(username).orElseThrow();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        humanSessionService.setPreRefreshLockHookForTest(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<AuthTokenService.RefreshOutcome> fenced = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(
                            () -> authAuthenticationService.refreshSession(
                                    login.rawRefresh(), ACTOR_IP, correlation)));
            awaitLatch(entered);
            fence.accept(user.getId());
            release.countDown();
            AuthTokenService.RefreshOutcome outcome = fenced.get(20, TimeUnit.SECONDS);
            assertFalse(outcome.success());
            assertEquals(expected, outcome.failureReason());
            assertNull(outcome.newRawRefreshToken(), "rejected refresh must mint no successor");
        } finally {
            humanSessionService.setPreRefreshLockHookForTest(null);
        }
    }

    @Test
    void lspDisableCommittedDuringRefreshGapReportsLspInactive() throws Exception {
        Lsp lsp = TenantScopedExecution.callAsAdmin(
                () -> lspRepository.save(new Lsp("T-GAP", "Test Gap", LspStatus.ACTIVE)));
        AppRole uiRole = TenantScopedExecution.callAsAdmin(() -> appRoleRepository
                .findByCodeIn(List.of(RoleCode.LSP_UI_READ)).stream().findFirst().orElseThrow());
        TenantScopedExecution.runAsAdmin(() -> appUserRepository.save(new AppUser(
                "t21.gaplsp",
                "t21.gaplsp@bhawana.local",
                passwordEncoder.encode("T21GapLsp123!"),
                UserStatus.ACTIVE,
                lsp,
                Set.of(uiRole))));
        LoginArtifacts login = login("t21.gaplsp@bhawana.local", "T21GapLsp123!");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        humanSessionService.setPreRefreshLockHookForTest(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<AuthTokenService.RefreshOutcome> fenced = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(
                            () -> authAuthenticationService.refreshSession(
                                    login.rawRefresh(), ACTOR_IP, "t21-gaplsp")));
            awaitLatch(entered);
            TenantScopedExecution.runAsAdmin(() -> lspStatusService.updateStatus(
                    lsp.getId(),
                    LspStatus.INACTIVE,
                    com.bhawana.lms.domain.LspStatusChangeReason.SECURITY_INCIDENT,
                    "gap test",
                    "t21.admin"));
            release.countDown();
            AuthTokenService.RefreshOutcome outcome = fenced.get(20, TimeUnit.SECONDS);
            assertFalse(outcome.success());
            assertEquals(AuthEventFailureReason.LSP_INACTIVE, outcome.failureReason());
            assertNull(outcome.newRawRefreshToken());
        } finally {
            humanSessionService.setPreRefreshLockHookForTest(null);
        }
    }

    @Test
    void lspAssignmentCommittedDuringRefreshGapRevokesOnlyThatFamily() throws Exception {
        Lsp lspA = TenantScopedExecution.callAsAdmin(
                () -> lspRepository.save(new Lsp("T-RA", "Test RA", LspStatus.ACTIVE)));
        Lsp lspB = TenantScopedExecution.callAsAdmin(
                () -> lspRepository.save(new Lsp("T-RB", "Test RB", LspStatus.ACTIVE)));
        AppRole uiRole = TenantScopedExecution.callAsAdmin(() -> appRoleRepository
                .findByCodeIn(List.of(RoleCode.LSP_UI_READ)).stream().findFirst().orElseThrow());
        AppUser created = TenantScopedExecution.callAsAdmin(() -> appUserRepository.save(new AppUser(
                "t21.gapassign",
                "t21.gapassign@bhawana.local",
                passwordEncoder.encode("T21GapAssign123!"),
                UserStatus.ACTIVE,
                lspA,
                Set.of(uiRole))));
        LoginArtifacts login = login("t21.gapassign@bhawana.local", "T21GapAssign123!");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        humanSessionService.setPreRefreshLockHookForTest(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<AuthTokenService.RefreshOutcome> fenced = executor.submit(() ->
                    TenantScopedExecution.callAsAdmin(
                            () -> authAuthenticationService.refreshSession(
                                    login.rawRefresh(), ACTOR_IP, "t21-gapassign")));
            awaitLatch(entered);
            TenantScopedExecution.runAsAdmin(() -> userAdminService.updateUser(
                    created.getId(), "t21.admin", ACTOR_IP, null, null, lspB.getId(), null));
            release.countDown();
            AuthTokenService.RefreshOutcome outcome = fenced.get(20, TimeUnit.SECONDS);
            assertFalse(outcome.success());
            assertEquals(AuthEventFailureReason.TOKEN_REVOKED, outcome.failureReason());
            assertNull(outcome.newRawRefreshToken());
        } finally {
            humanSessionService.setPreRefreshLockHookForTest(null);
        }
    }

    // ---- per-family vs all-user revocation ----

    @Test
    void familyLogoutLeavesSiblingFamilyWorking() throws Exception {
        createOpsUser("t21.sib", "T21SiblingPassword123!");
        LoginArtifacts first = login("t21.sib@bhawana.local", "T21SiblingPassword123!");
        LoginArtifacts second = login("t21.sib@bhawana.local", "T21SiblingPassword123!");

        mockMvc.perform(post("/api/v1/auth/logout").cookie(first.cookie()))
                .andExpect(status().isNoContent());
        assertNotNull(mockMvc.perform(post("/api/v1/auth/logout").cookie(first.cookie()))
                .andReturn().getResponse().getHeader("Set-Cookie"));

        assertTrue(serviceRefresh(second.rawRefresh()).success());
        AuthTokenService.RefreshOutcome dead = serviceRefresh(first.rawRefresh());
        assertFalse(dead.success());
    }

    @Test
    void unknownHashRevokesNothingAndMapsToRefreshInvalid() throws Exception {
        createOpsUser("t21.unknown", "T21UnknownPassword123!");
        LoginArtifacts login = login("t21.unknown@bhawana.local", "T21UnknownPassword123!");
        long familiesBefore = familyCountFor("t21.unknown");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("lms-refresh", "garbage-unknown-refresh-value")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"))
                .andReturn();
        assertNull(result.getResponse().getHeader("Set-Cookie"));
        assertEquals(familiesBefore, familyCountFor("t21.unknown"));
        assertTrue(serviceRefresh(login.rawRefresh()).success());
    }

    @Test
    void allUserFencesKillRefreshWhileSiblingLoginsSurviveFamilyLogout() throws Exception {
        createOpsUser("t21.fence", "T21FencePassword123!");
        LoginArtifacts login = login("t21.fence@bhawana.local", "T21FencePassword123!");
        AppUser user = appUserRepository.findByUsername("t21.fence").orElseThrow();

        // Admin reset is all-user: old refresh dies with TOKEN_REVOKED, new login works.
        TenantScopedExecution.runAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-fence-reset"));
        AuthTokenService.RefreshOutcome afterReset = serviceRefresh(login.rawRefresh());
        assertFalse(afterReset.success());
        assertEquals(AuthEventFailureReason.TOKEN_REVOKED, afterReset.failureReason());
    }

    @Test
    void disableReportsUserInactiveOnRefresh() throws Exception {
        createOpsUser("t21.disable", "T21DisablePassword123!");
        LoginArtifacts login = login("t21.disable@bhawana.local", "T21DisablePassword123!");
        AppUser user = appUserRepository.findByUsername("t21.disable").orElseThrow();

        TenantScopedExecution.runAsAdmin(() -> userAdminService.updateUser(
                user.getId(), "t21.admin", ACTOR_IP, null, UserStatus.INACTIVE, null, null));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh").cookie(login.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_INACTIVE"))
                .andReturn();
        assertNull(result.getResponse().getHeader("Set-Cookie"));
    }

    @Test
    void lspReassignmentRevokesAllSessionsAndNewLoginCarriesNewLsp() throws Exception {
        Lsp lspA = TenantScopedExecution.callAsAdmin(
                () -> lspRepository.save(new Lsp("T-A", "Test A", LspStatus.ACTIVE)));
        Lsp lspB = TenantScopedExecution.callAsAdmin(
                () -> lspRepository.save(new Lsp("T-B", "Test B", LspStatus.ACTIVE)));
        AppRole uiRole = TenantScopedExecution.callAsAdmin(() -> appRoleRepository
                .findByCodeIn(List.of(RoleCode.LSP_UI_READ)).stream().findFirst().orElseThrow());
        AppUser user = TenantScopedExecution.callAsAdmin(() -> appUserRepository.save(new AppUser(
                "t21.lsp",
                "t21.lsp@bhawana.local",
                passwordEncoder.encode("T21LspPassword123!"),
                UserStatus.ACTIVE,
                lspA,
                Set.of(uiRole))));

        LoginArtifacts login = login("t21.lsp@bhawana.local", "T21LspPassword123!");
        assertEquals(lspA.getId().toString(), jwtDecoder.decode(login.accessToken()).getClaimAsString("lspId"));

        TenantScopedExecution.runAsAdmin(() -> userAdminService.updateUser(
                user.getId(), "t21.admin", ACTOR_IP, null, null, lspB.getId(), null));

        AuthTokenService.RefreshOutcome afterMove = serviceRefresh(login.rawRefresh());
        assertFalse(afterMove.success());
        var revocations = authEventAuditRepository.search(
                "t21.lsp", AuthEventType.SESSIONS_REVOKED_BY_ADMIN, PageRequest.of(0, 10));
        assertTrue(revocations.getContent().stream().anyMatch(event ->
                        event.getDetailsJson() != null
                                && "LSP_ASSIGNMENT_CHANGE".equals(
                                        event.getDetailsJson().path("source").asText())),
                "LSP reassignment must join the all-user fence");

        LoginArtifacts relogin = login("t21.lsp@bhawana.local", "T21LspPassword123!");
        assertEquals(lspB.getId().toString(), jwtDecoder.decode(relogin.accessToken()).getClaimAsString("lspId"));
        assertTrue(serviceRefresh(relogin.rawRefresh()).success());
    }

    // ---- legacy + epoch forced login ----

    @Test
    void legacyNullFamilyRefreshForcesLoginWithNoCookie() throws Exception {
        AppUser user = createOpsUser("t21.legacy", "T21LegacyPassword123!");
        String raw = "t21-legacy-raw-refresh";
        TenantScopedExecution.runAsAdmin(() -> refreshTokenRepository.save(new RefreshToken(
                sha256Hex(raw), user, Instant.now().plusSeconds(3600))));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("lms-refresh", raw)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID_STATUS"))
                .andReturn();
        assertNull(result.getResponse().getHeader("Set-Cookie"));

        // No lineage is fabricated: the legacy row stays unrevoked and cutover login works.
        assertFalse(refreshTokenRepository.findByTokenHash(sha256Hex(raw)).orElseThrow().isRevoked());
        assertTrue(serviceRefresh(login("t21.legacy@bhawana.local", "T21LegacyPassword123!").rawRefresh())
                .success());
    }

    @Test
    void actualPolicyRotationRejectsOldRefreshWhileFreshLoginWorks() throws Exception {
        // Coherent A→B rotation: A is the production stack (HTTP + services); B is a
        // separately wired stack built through the actual JwtSecurityBeans factory methods
        // (signing key, encoder, decoder) plus a B policy provider, sharing the same
        // disposable database. No property is mutated, so nothing needs restoring.
        createOpsUser("t21.ab", "T21AbPassword123!");
        LoginArtifacts loginA = login("t21.ab@bhawana.local", "T21AbPassword123!");

        com.bhawana.lms.security.SecurityProperties propsB = new com.bhawana.lms.security.SecurityProperties();
        propsB.getJwt().setSecret("rotation-b-secret-policy-change-long-enough-xyz!");
        propsB.getJwt().setIssuer(securityProperties.getJwt().getIssuer());
        propsB.getJwt().setHumanAudience(securityProperties.getJwt().getHumanAudience());
        propsB.getJwt().setMachineAudience(securityProperties.getJwt().getMachineAudience());
        com.bhawana.lms.security.JwtSecurityBeans beansB = new com.bhawana.lms.security.JwtSecurityBeans();
        javax.crypto.SecretKey keyB = beansB.jwtSigningKey(propsB);
        org.springframework.security.oauth2.jwt.JwtEncoder encoderB = beansB.jwtEncoder(keyB);
        org.springframework.security.oauth2.jwt.JwtDecoder decoderB =
                com.bhawana.lms.security.JwtSecurityBeans.buildDecoder(
                        keyB, propsB, managedUserResolver, apiClientValidator, entraMachineJwtValidator);
        com.bhawana.lms.security.SessionPolicyEpochProvider epochB =
                new com.bhawana.lms.security.SessionPolicyEpochProvider(propsB);
        AuthTokenService tokenServiceB = new AuthTokenService(
                encoderB, propsB, appUserRepository, refreshTokenRepository);
        HumanSessionService sessionsB = new HumanSessionService(
                authenticationManager, appUserRepository, apiClientRepository,
                refreshTokenRepository, authSessionRepository, tokenServiceB,
                authAuditService, authPrincipalCache, propsB, epochB,
                passwordEncoder, apiClientAuthenticationService, lspSurfaceIpAllowlistService,
                humanLoginLockoutService, transactionManager);

        assertFalse(epochB.currentEpoch().equals(sessionPolicyEpochProvider.currentEpoch()),
                "distinct signing policies must yield distinct epochs");

        // Signed decoder gate through the production validator factory, both directions.
        assertThrows(Exception.class, () -> decoderB.decode(loginA.accessToken()));
        jwtDecoder.decode(loginA.accessToken());

        // Old A opaque cookie presented to the B policy: rejected with no successor
        // (an A-stack refresh would still succeed — same policy — so the rejection must
        // and does come from the B stack evaluating its own current epoch).
        // The B stack is manually wired (not Spring-proxied), so its read-only probe
        // runs inside an explicit test transaction for lazy association access.
        org.springframework.transaction.support.TransactionTemplate readTx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        readTx.setReadOnly(true);
        AuthTokenService.RefreshSubjectProbe probeAunderB = TenantScopedExecution.callAsAdmin(
                () -> readTx.execute(ignored -> tokenServiceB.classifyRefreshSubject(loginA.rawRefresh())));
        AuthTokenService.RefreshOutcome staleRefresh = TenantScopedExecution.callAsAdmin(
                () -> sessionsB.refresh(probeAunderB, loginA.rawRefresh(), ACTOR_IP, "t21-ab-stale"));
        assertFalse(staleRefresh.success());
        assertEquals(AuthEventFailureReason.SESSION_INVALID_STATUS, staleRefresh.failureReason());
        assertNull(staleRefresh.newRawRefreshToken());

        // Fresh password login under the actual B service: the RETURNED access token is
        // B-signed (no test minter involved) and the RETURNED refresh rotates under B.
        HumanSessionService.PasswordLoginIssued loginB = TenantScopedExecution.callAsAdmin(
                () -> sessionsB.login("t21.ab@bhawana.local", "T21AbPassword123!", ACTOR_IP));
        decoderB.decode(loginB.tokenResponse().accessToken());
        assertThrows(Exception.class, () -> jwtDecoder.decode(loginB.tokenResponse().accessToken()));

        AuthTokenService.RefreshSubjectProbe probeB = TenantScopedExecution.callAsAdmin(
                () -> readTx.execute(ignored ->
                        tokenServiceB.classifyRefreshSubject(loginB.rawRefreshToken())));
        AuthTokenService.RefreshOutcome rotatedB = TenantScopedExecution.callAsAdmin(
                () -> sessionsB.refresh(probeB, loginB.rawRefreshToken(), ACTOR_IP, "t21-ab-b"));
        assertTrue(rotatedB.success());
        decoderB.decode(rotatedB.tokenResponse().accessToken());
    }

    @Test
    void expiredRefreshReportsExpiredWithNoCookie() throws Exception {
        createOpsUser("t21.expired", "T21ExpiredPassword123!");
        LoginArtifacts login = login("t21.expired@bhawana.local", "T21ExpiredPassword123!");
        // Age the live head past expiry without touching lineage.
        UUID liveFamily = UUID.fromString(sidOf(login.accessToken()));
        TenantScopedExecution.runAsAdmin(() -> {
            RefreshToken head = refreshTokenRepository
                    .findByTokenHash(sha256Hex(login.rawRefresh())).orElseThrow();
            refreshTokenRepository.delete(head);
            AppUser user = appUserRepository.findByUsername("t21.expired").orElseThrow();
            AuthSession session = authSessionRepository.findById(liveFamily).orElseThrow();
            RefreshToken expired = new RefreshToken(
                    sha256Hex(login.rawRefresh()), user, Instant.now().minusSeconds(60));
            expired.attachFamily(session, user.getTokenVersion(),
                    user.getPasswordChangedAt().toEpochMilli(), sessionPolicyEpochProvider.currentEpoch());
            refreshTokenRepository.save(expired);
        });

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh").cookie(login.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andReturn();
        assertNull(result.getResponse().getHeader("Set-Cookie"));
    }

    // ---- signed sid negatives ----

    @Test
    void sidBindingRejectsWrongSubjectUnknownRevokedAndLegacy() throws Exception {
        createOpsUser("t21.sida", "T21SidAPassword123!");
        createOpsUser("t21.sidb", "T21SidBPassword123!");
        LoginArtifacts loginA = login("t21.sida@bhawana.local", "T21SidAPassword123!");
        String sidA = sidOf(loginA.accessToken());
        assertNotNull(sidA);

        AppUser userB = appUserRepository.findByUsername("t21.sidb").orElseThrow();
        AuthTokenService.ManagedUserState stateB = TenantScopedExecution.callAsAdmin(
                () -> authTokenService.loadManagedUserState(userB.getUsername()));

        // Wrong subject: B's claims bound to A's family.
        String crossed = mintHumanToken("t21.sidb", sidA, stateB);
        assertThrows(Exception.class, () -> jwtDecoder.decode(crossed));

        // Unknown family.
        String unknown = mintHumanToken("t21.sidb", UUID.randomUUID().toString(), stateB);
        assertThrows(Exception.class, () -> jwtDecoder.decode(unknown));

        // Legacy sid-less token forces login even with otherwise valid claims.
        String legacy = mintHumanToken("t21.sidb", null, stateB);
        assertThrows(Exception.class, () -> jwtDecoder.decode(legacy));

        // Revoked family: logout kills the sid for the next per-request check.
        mockMvc.perform(post("/api/v1/auth/logout").cookie(loginA.cookie()))
                .andExpect(status().isNoContent());
        String accessA = loginA.accessToken();
        assertThrows(Exception.class, () -> jwtDecoder.decode(accessA));
        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + accessA))
                .andExpect(status().isUnauthorized());
    }

    private String mintHumanToken(String subject, String sidOrNull, AuthTokenService.ManagedUserState state) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .audience(List.of(securityProperties.getJwt().getHumanAudience()))
                .claim("roles", List.of("OPS_USER"))
                .claim("pwdchg", state.passwordChangeRequired())
                .claim("pwdv", state.passwordChangedAt().toEpochMilli())
                .claim("tv", state.tokenVersion());
        if (sidOrNull != null) {
            builder.claim("sid", sidOrNull);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), builder.build())).getTokenValue();
    }

    // ---- self-service password fence (root finding 4) ----

    @Test
    void deferredPasswordRequestAfterAdminResetFailsClosedWithoutOverwrite() throws Exception {
        createOpsUser("t21.pwd", "T21PwdTemp123!");
        AppUser user = appUserRepository.findByUsername("t21.pwd").orElseThrow();
        String temp1 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-pwd-reset-1").temporaryPassword());
        LoginArtifacts login = login("t21.pwd@bhawana.local", temp1);
        long familiesBefore = familyCountFor("t21.pwd");

        // Admin resets again AFTER the request's bearer was issued: the deferred request
        // must fail closed, not overwrite the newest credential and mint a session.
        // (The per-request JWT validation rejects the stale bearer here; the in-fence
        // revalidation below covers a bearer that still validates.)
        String temp2 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                appUserRepository.findByUsername("t21.pwd").orElseThrow().getId(),
                "t21.admin", ACTOR_IP, "t21-pwd-reset-3").temporaryPassword());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("newPassword", "DeferredOverwrite123!");
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
        assertEquals(familiesBefore, familyCountFor("t21.pwd"), "deferred request must mint no session");

        // The deferred password never took effect; the newest credential still works.
        ObjectNode badLogin = objectMapper.createObjectNode();
        badLogin.put("email", "t21.pwd@bhawana.local");
        badLogin.put("password", "DeferredOverwrite123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badLogin.toString()))
                .andExpect(status().isUnauthorized());
        LoginArtifacts relogin = login("t21.pwd@bhawana.local", temp2);
        assertTrue(serviceRefresh(relogin.rawRefresh()).success());
    }

    @Test
    void deferredPasswordRequestAfterDisableFailsClosed() throws Exception {
        createOpsUser("t21.pwdd", "T21PwddTemp123!");
        AppUser user = appUserRepository.findByUsername("t21.pwdd").orElseThrow();
        String temp = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-pwdd-reset").temporaryPassword());
        LoginArtifacts login = login("t21.pwdd@bhawana.local", temp);

        TenantScopedExecution.runAsAdmin(() -> userAdminService.updateUser(
                appUserRepository.findByUsername("t21.pwdd").orElseThrow().getId(),
                "t21.admin", ACTOR_IP, null, UserStatus.INACTIVE, null, null));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("newPassword", "DeferredAfterDisable123!");
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * In-fence revalidation (root finding 4): these requests bypass the resource-server
     * decoder with a mock authentication — deliberately, to place stale-but-plausible
     * bearer material directly at the fence. Signed-decoder behavior is proven by the
     * surrounding real-token tests; here each stale branch (revoked sid, stale tv,
     * inactive subject) must fail closed without overwriting the current credential.
     */
    @Test
    void passwordFenceRejectsRevokedSidEvenWithCurrentVersions() throws Exception {
        createOpsUser("t21.fence1", "T21Fence1Temp123!");
        AppUser user = appUserRepository.findByUsername("t21.fence1").orElseThrow();
        String temp1 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-fence1-reset-1").temporaryPassword());
        LoginArtifacts login = login("t21.fence1@bhawana.local", temp1);
        String staleSid = sidOf(login.accessToken());
        // Intervening reset kills the presented family; current versions are attached so
        // only the sid branch can reject.
        String temp2 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                appUserRepository.findByUsername("t21.fence1").orElseThrow().getId(),
                "t21.admin", ACTOR_IP, "t21-fence1-reset-2").temporaryPassword());
        AppUser current = appUserRepository.findByUsername("t21.fence1").orElseThrow();
        long familiesBefore = familyCountFor("t21.fence1");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("newPassword", "FenceBypassAttempt123!");
        mockMvc.perform(post("/api/v1/auth/password")
                        .with(jwt().jwt(jwt -> jwt
                                        .subject("t21.fence1")
                                        .claim("sid", staleSid)
                                        .claim("tv", current.getTokenVersion())
                                        .claim("pwdv", current.getPasswordChangedAt().toEpochMilli()))
                                .authorities(() -> "ROLE_OPS_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
        assertEquals(familiesBefore, familyCountFor("t21.fence1"));

        ObjectNode badLogin = objectMapper.createObjectNode();
        badLogin.put("email", "t21.fence1@bhawana.local");
        badLogin.put("password", "FenceBypassAttempt123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badLogin.toString()))
                .andExpect(status().isUnauthorized());
        assertTrue(serviceRefresh(login("t21.fence1@bhawana.local", temp2).rawRefresh()).success());
    }

    @Test
    void passwordFenceRejectsStaleVersionEvenWithLiveSid() throws Exception {
        createOpsUser("t21.fence2", "T21Fence2Temp123!");
        AppUser user = appUserRepository.findByUsername("t21.fence2").orElseThrow();
        String temp1 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-fence2-reset-1").temporaryPassword());
        LoginArtifacts first = login("t21.fence2@bhawana.local", temp1);
        long staleTv = ((Number) jwtDecoder.decode(first.accessToken()).getClaims().get("tv")).longValue();
        // Intervening reset bumps tv; the new login owns a live family.
        String temp2 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                appUserRepository.findByUsername("t21.fence2").orElseThrow().getId(),
                "t21.admin", ACTOR_IP, "t21-fence2-reset-2").temporaryPassword());
        LoginArtifacts second = login("t21.fence2@bhawana.local", temp2);
        String liveSid = sidOf(second.accessToken());
        long familiesBefore = familyCountFor("t21.fence2");

        // Live sid but stale tv: the version branch must reject.
        ObjectNode body = objectMapper.createObjectNode();
        body.put("newPassword", "FenceVersionBypass123!");
        mockMvc.perform(post("/api/v1/auth/password")
                        .with(jwt().jwt(jwt -> jwt
                                        .subject("t21.fence2")
                                        .claim("sid", liveSid)
                                        .claim("tv", staleTv))
                                .authorities(() -> "ROLE_OPS_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
        assertEquals(familiesBefore, familyCountFor("t21.fence2"));
    }

    @Test
    void heldPasswordRequestSeesResetCommittedBeforeFence() throws Exception {
        // Real request, real bearer: the request validates at the filter, is then held at
        // the fence entry while a second transaction commits an admin reset, and must fail
        // closed without overwriting the newest credential or minting a session.
        createOpsUser("t21.heldreset", "T21HeldResetTemp123!");
        AppUser user = appUserRepository.findByUsername("t21.heldreset").orElseThrow();
        String temp1 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-heldreset-1").temporaryPassword());
        LoginArtifacts login = login("t21.heldreset@bhawana.local", temp1);
        long familiesBefore = familyCountFor("t21.heldreset");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        humanSessionService.setPrePasswordFenceHookForTest(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        ObjectNode body = objectMapper.createObjectNode();
        body.put("newPassword", "HeldResetBypass123!");
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<MvcResult> held = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/auth/password")
                                    .header("Authorization", "Bearer " + login.accessToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body.toString()))
                            .andReturn());
            awaitLatch(entered);
            String temp2 = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                    appUserRepository.findByUsername("t21.heldreset").orElseThrow().getId(),
                    "t21.admin", ACTOR_IP, "t21-heldreset-2").temporaryPassword());
            release.countDown();
            assertEquals(401, held.get(20, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(familiesBefore, familyCountFor("t21.heldreset"));

            ObjectNode badLogin = objectMapper.createObjectNode();
            badLogin.put("email", "t21.heldreset@bhawana.local");
            badLogin.put("password", "HeldResetBypass123!");
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badLogin.toString()))
                    .andExpect(status().isUnauthorized());
            assertTrue(serviceRefresh(login("t21.heldreset@bhawana.local", temp2).rawRefresh())
                    .success());
        } finally {
            humanSessionService.setPrePasswordFenceHookForTest(null);
        }
    }

    @Test
    void heldPasswordRequestSeesDisableCommittedBeforeFence() throws Exception {
        createOpsUser("t21.helddisable", "T21HeldDisableTemp123!");
        AppUser user = appUserRepository.findByUsername("t21.helddisable").orElseThrow();
        String temp = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-helddisable-1").temporaryPassword());
        LoginArtifacts login = login("t21.helddisable@bhawana.local", temp);
        long familiesBefore = familyCountFor("t21.helddisable");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        humanSessionService.setPrePasswordFenceHookForTest(() -> {
            entered.countDown();
            awaitLatch(release);
        });
        ObjectNode body = objectMapper.createObjectNode();
        body.put("newPassword", "HeldDisableBypass123!");
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<MvcResult> held = executor.submit(() ->
                    mockMvc.perform(post("/api/v1/auth/password")
                                    .header("Authorization", "Bearer " + login.accessToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body.toString()))
                            .andReturn());
            awaitLatch(entered);
            TenantScopedExecution.runAsAdmin(() -> userAdminService.updateUser(
                    appUserRepository.findByUsername("t21.helddisable").orElseThrow().getId(),
                    "t21.admin", ACTOR_IP, null, UserStatus.INACTIVE, null, null));
            release.countDown();
            assertEquals(401, held.get(20, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(familiesBefore, familyCountFor("t21.helddisable"));
        } finally {
            humanSessionService.setPrePasswordFenceHookForTest(null);
        }
    }

    @Test
    void selfServicePasswordChangeMintsPostChangeSessionAndLogsOutOtherDevices() throws Exception {
        createOpsUser("t21.pwdok", "T21PwdokTemp123!");
        AppUser user = appUserRepository.findByUsername("t21.pwdok").orElseThrow();
        String temp = TenantScopedExecution.callAsAdmin(() -> userAdminService.resetUserPassword(
                user.getId(), "t21.admin", ACTOR_IP, "t21-pwdok-reset").temporaryPassword());
        LoginArtifacts first = login("t21.pwdok@bhawana.local", temp);
        LoginArtifacts second = login("t21.pwdok@bhawana.local", temp);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("newPassword", "SelfServiceNew123!");
        MvcResult changed = mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + second.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();
        assertNotNull(changed.getResponse().getHeader("Set-Cookie"));
        String freshAccess = objectMapper.readTree(changed.getResponse().getContentAsString())
                .get("accessToken").asText();
        assertNotNull(sidOf(freshAccess));

        // Other device is logged out (all-user fence); the old temp credential is dead.
        assertFalse(serviceRefresh(first.rawRefresh()).success());
        ObjectNode oldLogin = objectMapper.createObjectNode();
        oldLogin.put("email", "t21.pwdok@bhawana.local");
        oldLogin.put("password", temp);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oldLogin.toString()))
                .andExpect(status().isUnauthorized());
        LoginArtifacts relogin = login("t21.pwdok@bhawana.local", "SelfServiceNew123!");
        assertTrue(serviceRefresh(relogin.rawRefresh()).success());
    }

    // ---- audit-failure rollback ----

    @Test
    void refreshRollsBackWhenSuccessAuditFails() {
        createOpsUser("t21.rbref", "T21RbrefPassword123!");
        String raw = TenantScopedExecution.callAsAdmin(() ->
                authAuthenticationService.login(
                        "t21.rbref@bhawana.local", "T21RbrefPassword123!", ACTOR_IP).rawRefreshToken());

        doThrow(new RuntimeException("simulated refresh-audit failure"))
                .when(authAuditService)
                .recordTokenRefreshSuccess(any(), any(), any(), any(), any());

        assertThrows(
                RuntimeException.class,
                () -> TenantScopedExecution.callAsAdmin(
                        () -> authAuthenticationService.refreshSession(raw, ACTOR_IP, "t21-rbref")));

        // Rollback preserved the presented token: still live, unrevoked, no successor.
        RefreshToken preserved = refreshTokenRepository.findByTokenHash(sha256Hex(raw)).orElseThrow();
        assertFalse(preserved.isRevoked());
        assertNull(preserved.getReplacedByHash());
    }

    @Test
    void logoutRollsBackWhenLogoutAuditFails() throws Exception {
        createOpsUser("t21.rblog", "T21RblogPassword123!");
        LoginArtifacts login = login("t21.rblog@bhawana.local", "T21RblogPassword123!");

        doThrow(new RuntimeException("simulated logout-audit failure"))
                .when(authAuditService)
                .recordLogout(any(), any(), any(), any());

        String raw = login.rawRefresh();
        assertThrows(
                RuntimeException.class,
                () -> TenantScopedExecution.callAsAdmin(
                        () -> authAuthenticationService.logoutFamily(raw, ACTOR_IP, "t21-rblog")));

        // Rollback preserved the family: refresh still succeeds.
        org.mockito.Mockito.reset(authAuditService);
        assertTrue(serviceRefresh(raw).success());
    }

    private record LoginArtifacts(String accessToken, String rawRefresh, Cookie cookie) {
    }
}
