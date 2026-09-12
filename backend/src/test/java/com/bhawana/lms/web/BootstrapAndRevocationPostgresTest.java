package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.AuthPrincipalCache;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.service.AuthTokenService;
import com.bhawana.lms.service.LocalBootstrapAdminSyncService;
import com.bhawana.lms.service.UserAdminService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Foundation regression: missing-user JWT fallback and bootstrap password resynchronization.
 *
 * <p>Human JWTs must require an active existing managed principal on every protected surface; a
 * signed token for a deleted user authenticates nothing (after at most the documented bounded
 * cache window — tests evict to assert the steady state). Bootstrap startup creates only when
 * absent and never silently resets password, roles, status or lock state. The explicit password
 * reset path for any managed user (including the bootstrap admin) is the existing authorized
 * admin reset ({@link UserAdminService#resetUserPassword}, peer SYSTEM_ADMIN, audited, sessions
 * revoked) — bootstrap sync itself never resets credentials.
 *
 * <p>Fixture hygiene: tests that delete the shared bootstrap row or mint fixture users restore
 * the canonical bootstrap state afterwards, so no globally disabled/password-changed bootstrap
 * identity leaks into unrelated suites sharing the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class BootstrapAndRevocationPostgresTest {

    private static final List<String> FIXTURE_USERS = List.of(
            "t12.deleted", "t12.disabled", "t12.sync.actor", "t12.restore.actor",
            "t12.recovery.peer", "ghost.admin");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private AppRoleRepository appRoleRepository;
    @Autowired private AppUserAuditEventRepository appUserAuditEventRepository;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private AuthPrincipalCache authPrincipalCache;
    @Autowired private LocalBootstrapAdminSyncService bootstrapAdminSyncService;
    @Autowired private UserAdminService userAdminService;
    @Autowired private SecurityProperties securityProperties;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.bhawana.lms.service.HumanSessionTestFixtures humanSessionTestFixtures;

    @BeforeEach
    void ensureBootstrapRowExists() {
        TenantScopedExecution.runAsAdmin(this::restoreCanonicalBootstrap);
    }

    @AfterEach
    void restoreSharedState() {
        TenantScopedExecution.runAsAdmin(() -> {
            // FK from app_user_audit_event (and refresh_token) to app_user requires audit
            // cleanup before row deletes; scoped to this class's fixture identities.
            for (String username : FIXTURE_USERS) {
                deleteUserRow(username);
            }
            restoreCanonicalBootstrap();
        });
    }

    @Test
    void deletedUserSignedTokenFailsDecoderAndProtectedSurface() throws Exception {
        createManagedUser("t12.deleted", "DeletedPassword123!");
        String token = TenantScopedExecution.callAsAdmin(
                () -> humanSessionTestFixtures.mintHumanTokenForUsername("t12.deleted").accessToken());

        TenantScopedExecution.runAsAdmin(() -> deleteUserRow("t12.deleted"));
        // Steady state past the bounded principal-cache window.
        authPrincipalCache.evictAppUser("t12.deleted");

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));

        for (String surface : List.of(
                "/api/v1/internal/system/context",
                "/api/v1/internal/admin/users",
                "/api/v1/internal/ops/loan-applications",
                "/api/v1/lsp/loan-applications",
                "/api/v1/lsp/loans",
                "/api/v1/lsp/borrowers",
                "/api/v1/lsp/products",
                "/v3/api-docs",
                "/actuator/prometheus")) {
            mockMvc.perform(get(surface).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"DeletedSubjectCannotReset123!\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/internal/system/bootstrap-sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserSignedTokenFailsDecoderAndProtectedSurface() throws Exception {
        createManagedUser("t12.disabled", "DisabledPassword123!");
        String token = TenantScopedExecution.callAsAdmin(
                () -> humanSessionTestFixtures.mintHumanTokenForUsername("t12.disabled").accessToken());

        TenantScopedExecution.runAsAdmin(() -> {
            AppUser managed = appUserRepository.findByUsername("t12.disabled").orElseThrow();
            managed.changeStatus(UserStatus.INACTIVE);
            appUserRepository.save(managed);
        });
        authPrincipalCache.evictAppUser("t12.disabled");

        assertThrows(Exception.class, () -> jwtDecoder.decode(token));

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mintingForMissingOrInactiveUserFailsClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> TenantScopedExecution.callAsAdmin(
                        () -> authTokenService.loadManagedUserState("ghost.admin")));

        createManagedUser("t12.disabled", "DisabledPassword123!");
        TenantScopedExecution.runAsAdmin(() -> {
            AppUser managed = appUserRepository.findByUsername("t12.disabled").orElseThrow();
            if (managed.getStatus() == UserStatus.ACTIVE) {
                managed.changeStatus(UserStatus.INACTIVE);
                appUserRepository.save(managed);
            }
        });
        authPrincipalCache.evictAppUser("t12.disabled");

        assertThrows(
                IllegalStateException.class,
                () -> TenantScopedExecution.callAsAdmin(
                        () -> authTokenService.loadManagedUserState("t12.disabled")));
    }

    @Test
    void restartPreservesChangedPasswordRolesStatusAndLock() {
        createManagedUser("t12.disabled", "DisabledPassword123!");
        String username = bootstrapUsername();
        OriginalState original = TenantScopedExecution.callAsAdmin(() -> {
            AppUser user = appUserRepository.findByUsername(username).orElseThrow();
            return new OriginalState(
                    user.getPasswordHash(), user.getStatus(),
                    roleCodes(user), user.getLockedAt(), user.getLockReason());
        });

        try {
            TenantScopedExecution.runAsAdmin(() -> {
                AppUser user = appUserRepository.findByUsername(username).orElseThrow();
                user.updatePasswordHash(passwordEncoder.encode("OperatorChangedPassword999!"));
                user.changeStatus(UserStatus.INACTIVE);
                AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                        .findFirst().orElseThrow();
                user.updateManagedProfile(user.getEmail(), null, Set.of(opsRole));
                user.lockForBruteForce(Instant.now());
                appUserRepository.save(user);
            });
            authPrincipalCache.evictAppUser(username);

            TenantScopedExecution.runAsAdmin(() -> bootstrapAdminSyncService.run(
                    new DefaultApplicationArguments(new String[0])));

            TenantScopedExecution.runAsAdmin(() -> {
                AppUser restarted = appUserRepository.findByUsername(username).orElseThrow();
                assertTrue(passwordEncoder.matches("OperatorChangedPassword999!", restarted.getPasswordHash()));
                assertEquals(UserStatus.INACTIVE, restarted.getStatus());
                assertEquals(Set.of(RoleCode.OPS_USER), roleCodes(restarted));
                assertTrue(restarted.isLocked());
            });
        } finally {
            TenantScopedExecution.runAsAdmin(() -> {
                AppUser user = appUserRepository.findByUsername(username).orElseThrow();
                user.updatePasswordHash(original.passwordHash());
                if (user.getStatus() != original.status()) {
                    user.changeStatus(original.status());
                }
                Set<AppRole> roles = new LinkedHashSet<>(
                        appRoleRepository.findByCodeIn(List.copyOf(original.roles())));
                user.updateManagedProfile(user.getEmail(), null, roles);
                user.unlockForReset();
                appUserRepository.save(user);
            });
            authPrincipalCache.evictAppUser(username);
        }
    }

    @Test
    void explicitSyncDoesNotMutateExistingBootstrapUser() {
        String actor = createRecoveryAdmin("t12.sync.actor");
        String username = bootstrapUsername();
        String hashBefore = TenantScopedExecution.callAsAdmin(
                () -> appUserRepository.findByUsername(username).orElseThrow().getPasswordHash());

        TenantScopedExecution.runAsAdmin(
                () -> bootstrapAdminSyncService.syncBootstrapAdmin(actor, "t12-sync-noop"));

        TenantScopedExecution.runAsAdmin(() -> {
            AppUser user = appUserRepository.findByUsername(username).orElseThrow();
            assertEquals(hashBefore, user.getPasswordHash());
            assertEquals(UserStatus.ACTIVE, user.getStatus());
            var audit = appUserAuditEventRepository
                    .findTopByUser_IdOrderByCreatedAtDesc(user.getId()).orElseThrow();
            assertEquals(actor, audit.getActorUsername());
            assertFalse(auditText(audit).toLowerCase().contains("password"));
            assertFalse(auditText(audit).contains(hashBefore));
        });
    }

    @Test
    void explicitSyncRestoresDeletedBootstrapUserWithPasswordFreeAudit() {
        String actor = createRecoveryAdmin("t12.restore.actor");
        String username = bootstrapUsername();
        TenantScopedExecution.runAsAdmin(() -> deleteUserRow(username));

        TenantScopedExecution.runAsAdmin(
                () -> bootstrapAdminSyncService.syncBootstrapAdmin(actor, "t12-sync-restore"));

        TenantScopedExecution.runAsAdmin(() -> {
            AppUser restored = appUserRepository.findByUsername(username).orElseThrow();
            assertEquals(UserStatus.ACTIVE, restored.getStatus());
            assertTrue(restored.getRoles().stream()
                    .anyMatch(role -> role.getCode() == RoleCode.SYSTEM_ADMIN));
            var audit = appUserAuditEventRepository
                    .findTopByUser_IdOrderByCreatedAtDesc(restored.getId()).orElseThrow();
            assertEquals(actor, audit.getActorUsername());
            assertEquals("RESTORED", audit.getAfterStateJson().get("result").asText());
            String auditText = auditText(audit);
            assertFalse(auditText.toLowerCase().contains("password"));
            assertFalse(auditText.contains(restored.getPasswordHash()));
        });
    }

    @Test
    void peerAdminResetIsTheExplicitResetPathForBootstrapUser() {
        String peer = createRecoveryAdmin("t12.recovery.peer");
        String username = bootstrapUsername();
        String tokenBefore = TenantScopedExecution.callAsAdmin(
                () -> humanSessionTestFixtures.mintHumanTokenForUsername(username).accessToken());
        long versionBefore = TenantScopedExecution.callAsAdmin(
                () -> appUserRepository.findByUsername(username).orElseThrow().getTokenVersion());

        UserAdminService.ResetPasswordResult reset = TenantScopedExecution.callAsAdmin(
                () -> userAdminService.resetUserPassword(
                        appUserRepository.findByUsername(username).orElseThrow().getId(),
                        peer,
                        "127.0.0.1",
                        "t12-peer-reset"));

        TenantScopedExecution.runAsAdmin(() -> {
            AppUser user = appUserRepository.findByUsername(username).orElseThrow();
            assertTrue(user.isPasswordChangeRequired());
            assertTrue(user.getTokenVersion() > versionBefore);
            assertTrue(passwordEncoder.matches(reset.temporaryPassword(), user.getPasswordHash()));
            var audit = appUserAuditEventRepository
                    .findTopByUser_IdOrderByCreatedAtDesc(user.getId()).orElseThrow();
            assertEquals("PASSWORD_RESET_BY_ADMIN", audit.getAfterStateJson().get("eventType").asText());
            assertEquals(peer, audit.getActorUsername());
            String auditText = auditText(audit);
            assertFalse(auditText.contains(reset.temporaryPassword()));
            assertFalse(auditText.contains(user.getPasswordHash()));
        });
        authPrincipalCache.evictAppUser(username);

        // Pre-reset sessions are revoked: the earlier token no longer verifies.
        assertThrows(Exception.class, () -> jwtDecoder.decode(tokenBefore));
    }

    @Test
    void deletedActorCannotRestoreThroughSyncRoute() {
        TenantScopedExecution.runAsAdmin(() -> deleteUserRow("ghost.admin"));
        authPrincipalCache.evictAppUser("ghost.admin");

        assertThrows(
                IllegalStateException.class,
                () -> TenantScopedExecution.runAsAdmin(
                        () -> bootstrapAdminSyncService.syncBootstrapAdmin("ghost.admin", "t12-ghost"))
        );
    }

    @Test
    void deletedBootstrapUserCannotLoginThroughConfigFallback() throws Exception {
        String username = bootstrapUsername();
        String email = securityProperties.getBootstrapUser().getEmail();
        String configuredPassword = securityProperties.getBootstrapUser().getPassword();
        TenantScopedExecution.runAsAdmin(() -> deleteUserRow(username));
        authPrincipalCache.evictAppUser(username);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("email", email);
            body.put("password", configuredPassword);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.toString()))
                    .andExpect(status().isUnauthorized());
        } finally {
            ensureBootstrapRowExists();
        }
    }

    @Test
    void activeBootstrapUserCanStillLogin() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", securityProperties.getBootstrapUser().getEmail());
        body.put("password", securityProperties.getBootstrapUser().getPassword());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk());
    }

    private AppUser createManagedUser(String username, String rawPassword) {
        return TenantScopedExecution.callAsAdmin(() -> {
            deleteUserRow(username);
            AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                    .findFirst().orElseThrow();
            AppUser user = new AppUser(
                    username,
                    username + "@bhawana.local",
                    passwordEncoder.encode(rawPassword),
                    UserStatus.ACTIVE,
                    null,
                    Set.of(opsRole)
            );
            return appUserRepository.save(user);
        });
    }

    private String createRecoveryAdmin(String username) {
        return TenantScopedExecution.callAsAdmin(() -> {
            deleteUserRow(username);
            AppUser admin = new AppUser(
                    username,
                    username + "@bhawana.local",
                    passwordEncoder.encode("RecoveryAdmin123!"),
                    UserStatus.ACTIVE,
                    null,
                    Set.of(adminRole())
            );
            appUserRepository.save(admin);
            authPrincipalCache.evictAppUser(username);
            return username;
        });
    }

    private void deleteUserRow(String username) {
        appUserRepository.findByUsername(username).ifPresent(existing -> {
            appUserAuditEventRepository.deleteAll(appUserAuditEventRepository.findAll().stream()
                    .filter(event -> existing.getId().equals(event.getUserId()))
                    .toList());
            appUserRepository.delete(existing);
        });
        authPrincipalCache.evictAppUser(username);
    }

    /**
     * Canonical test bootstrap state: configured identity, SYSTEM_ADMIN, ACTIVE, unlocked, and
     * the configured password usable for login. Runs before each test and restores the shared
     * row afterwards so mutations never leak into unrelated suites.
     */
    private void restoreCanonicalBootstrap() {
        String username = bootstrapUsername();
        String email = securityProperties.getBootstrapUser().getEmail();
        String configuredPassword = securityProperties.getBootstrapUser().getPassword();
        AppUser user = appUserRepository.findByUsername(username)
                .orElseGet(() -> appUserRepository.save(new AppUser(
                        username,
                        email,
                        passwordEncoder.encode(configuredPassword),
                        UserStatus.ACTIVE,
                        null,
                        Set.of(adminRole())
                )));
        user.changePassword(passwordEncoder.encode(configuredPassword));
        if (user.getStatus() != UserStatus.ACTIVE) {
            user.changeStatus(UserStatus.ACTIVE);
        }
        user.updateManagedProfile(email, null, Set.of(adminRole()));
        user.unlockForReset();
        appUserRepository.save(user);
        authPrincipalCache.evictAppUser(username);
    }

    private AppRole adminRole() {
        return appRoleRepository.findByCodeIn(List.of(RoleCode.SYSTEM_ADMIN)).stream()
                .findFirst().orElseThrow();
    }

    private String bootstrapUsername() {
        return securityProperties.getBootstrapUser().getUsername().trim().toLowerCase();
    }

    private static Set<RoleCode> roleCodes(AppUser user) {
        Set<RoleCode> codes = new LinkedHashSet<>();
        user.getRoles().forEach(role -> codes.add(role.getCode()));
        return codes;
    }

    private static String auditText(com.bhawana.lms.domain.AppUserAuditEvent audit) {
        return audit.getBeforeStateJson().toString() + audit.getAfterStateJson().toString();
    }

    private record OriginalState(
            String passwordHash, UserStatus status, Set<RoleCode> roles,
            java.time.Instant lockedAt, String lockReason) {
    }
}
