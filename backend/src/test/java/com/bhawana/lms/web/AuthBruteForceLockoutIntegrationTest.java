package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AppUserAuditEvent;
import com.bhawana.lms.domain.AuthEventType;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.service.AlertRuleEvaluationWorker;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AuthBruteForceLockoutIntegrationTest {

    private static final String CLIENT_IP = "198.51.100.42";
    private static final String OTHER_IP = "203.0.113.99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private com.bhawana.lms.repo.AppRoleRepository appRoleRepository;

    @Autowired
    private AuthEventAuditRepository authEventAuditRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private AppUserAuditEventRepository appUserAuditEventRepository;

    @Autowired
    private AlertRuleEvaluationWorker alertRuleEvaluationWorker;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUpUsers() {
        authEventAuditRepository.deleteAll();
        appUserAuditEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        opsAlertRepository.deleteAll();
        appUserRepository.deleteAll();

        AppRole opsUserRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();

        appUserRepository.save(new AppUser(
                "sarah.user",
                "sarah.user@bhawana.local",
                passwordEncoder.encode("SarahPassword123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(opsUserRole)
        ));
    }

    @Test
    void five_failed_logins_from_same_username_and_ip_within_ten_minutes_locks_the_user_and_revokes_sessions()
            throws Exception {
        LoginArtifacts session = login("sarah.user", "SarahPassword123!", CLIENT_IP);

        for (int attempt = 0; attempt < 5; attempt++) {
            attemptLogin("sarah.user", "WrongPassword!", CLIENT_IP)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        alertRuleEvaluationWorker.evaluateScheduledRules();

        entityManager.clear();
        AppUser lockedUser = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNotNull(lockedUser.getLockedAt());
        assertEquals(AppUser.LOCK_REASON_BRUTE_FORCE, lockedUser.getLockReason());

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(session.refreshCookie()))
                .andExpect(status().isUnauthorized());

        assertTrue(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.AUTH_BRUTE_FORCE,
                lockedUser.getId(),
                OpsAlertStatus.NEW
        ));

        attemptLogin("sarah.user", "SarahPassword123!", CLIENT_IP)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN"))
                        .param("username", "sarah.user")
                        .param("eventType", "SESSIONS_REVOKED_BY_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].details.source").value("BRUTE_FORCE_LOCKOUT"));
    }

    @Test
    void four_failed_logins_from_same_username_and_ip_do_not_trigger_lockout() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            attemptLogin("sarah.user", "WrongPassword!", CLIENT_IP)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        alertRuleEvaluationWorker.evaluateScheduledRules();

        entityManager.clear();
        AppUser user = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNull(user.getLockedAt());
        assertNull(user.getLockReason());

        login("sarah.user", "SarahPassword123!", CLIENT_IP);
    }

    @Test
    void failed_logins_split_across_two_ips_below_strict_threshold_do_not_trigger_strict_lockout() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            attemptLogin("sarah.user", "WrongPassword!", "198.51.100.10")
                    .andExpect(status().isUnauthorized());
            attemptLogin("sarah.user", "WrongPassword!", "198.51.100.11")
                    .andExpect(status().isUnauthorized());
        }

        alertRuleEvaluationWorker.evaluateScheduledRules();

        entityManager.clear();
        AppUser user = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNull(user.getLockedAt());
        assertFalse(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.AUTH_BRUTE_FORCE,
                user.getId(),
                OpsAlertStatus.NEW
        ));
    }

    @Test
    void correct_login_from_different_ip_against_locked_user_also_returns_same_401_shape() throws Exception {
        lockUserViaBruteForce();

        attemptLogin("sarah.user", "SarahPassword123!", OTHER_IP)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void twenty_failed_logins_across_five_distinct_ips_fires_distributed_alert_but_does_not_lock() throws Exception {
        String[] ips = {
                "198.51.100.21",
                "198.51.100.22",
                "198.51.100.23",
                "198.51.100.24",
                "198.51.100.25"
        };
        for (String ip : ips) {
            for (int attempt = 0; attempt < 4; attempt++) {
                attemptLogin("sarah.user", "WrongPassword!", ip)
                        .andExpect(status().isUnauthorized());
            }
        }

        alertRuleEvaluationWorker.evaluateScheduledRules();

        entityManager.clear();
        AppUser user = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNull(user.getLockedAt());
        assertTrue(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.AUTH_BRUTE_FORCE_DISTRIBUTED,
                user.getId(),
                OpsAlertStatus.NEW
        ));
        assertFalse(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.AUTH_BRUTE_FORCE,
                user.getId(),
                OpsAlertStatus.NEW
        ));
    }

    @Test
    void repeated_rule_evaluation_over_already_locked_user_is_idempotent_no_duplicate_alerts() throws Exception {
        lockUserViaBruteForce();

        entityManager.clear();
        AppUser lockedUser = appUserRepository.findByUsername("sarah.user").orElseThrow();
        Instant lockedAt = lockedUser.getLockedAt();
        long tokenVersion = lockedUser.getTokenVersion();

        alertRuleEvaluationWorker.evaluateScheduledRules();
        alertRuleEvaluationWorker.evaluateScheduledRules();

        entityManager.clear();
        AppUser stillLocked = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertEquals(lockedAt, stillLocked.getLockedAt());
        assertEquals(tokenVersion, stillLocked.getTokenVersion());
        assertEquals(1, countOpenAlerts(OpsAlertType.AUTH_BRUTE_FORCE, stillLocked.getId()));
    }

    @Test
    void admin_reset_password_clears_lock_and_records_unlock_audit_fields() throws Exception {
        AppUser user = lockUserViaBruteForce();

        MvcResult resetResult = mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", user.getId())
                        .with(jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();

        String temporaryPassword = objectMapper.readTree(resetResult.getResponse().getContentAsString())
                .get("temporaryPassword")
                .asText();

        entityManager.clear();
        AppUser unlocked = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNull(unlocked.getLockedAt());
        assertNull(unlocked.getLockReason());
        assertTrue(unlocked.isPasswordChangeRequired());

        AppUserAuditEvent auditEvent = appUserAuditEventRepository
                .findTopByUser_IdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();
        assertEquals("PASSWORD_RESET_BY_ADMIN", auditEvent.getAfterStateJson().get("eventType").asText());
        assertTrue(auditEvent.getAfterStateJson().get("unlockedFromAutoLockout").asBoolean());
        assertEquals(AppUser.LOCK_REASON_BRUTE_FORCE, auditEvent.getAfterStateJson().get("priorLockReason").asText());

        attemptLogin("sarah.user", temporaryPassword, CLIENT_IP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void login_attempt_with_wrong_password_during_lockout_still_records_audit_rows_for_distributed_evidence()
            throws Exception {
        lockUserViaBruteForce();
        long loginFailedBefore = countLoginFailedEvents("sarah.user");

        for (int attempt = 0; attempt < 5; attempt++) {
            attemptLogin("sarah.user", "WrongPassword!", "198.51.100." + (30 + attempt))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        assertEquals(loginFailedBefore + 5, countLoginFailedEvents("sarah.user"));

        entityManager.clear();
        AppUser user = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNotNull(user.getLockedAt());
        alertRuleEvaluationWorker.evaluateScheduledRules();
        assertEquals(1, countOpenAlerts(OpsAlertType.AUTH_BRUTE_FORCE, user.getId()));
    }

    @Test
    void proactive_refresh_failures_do_not_count_toward_login_failed_or_advance_rules() throws Exception {
        long loginFailedBefore = countLoginFailedEvents("sarah.user");

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("lms-refresh", "unknown-refresh-token-value")))
                .andExpect(status().isUnauthorized());

        assertEquals(loginFailedBefore, countLoginFailedEvents("sarah.user"));

        for (int attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(post("/api/v1/auth/refresh"))
                    .andExpect(status().isUnauthorized());
        }

        alertRuleEvaluationWorker.evaluateScheduledRules();

        entityManager.clear();
        AppUser user = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNull(user.getLockedAt());
        assertFalse(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.AUTH_BRUTE_FORCE,
                user.getId(),
                OpsAlertStatus.NEW
        ));
        assertFalse(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.AUTH_BRUTE_FORCE_DISTRIBUTED,
                user.getId(),
                OpsAlertStatus.NEW
        ));
    }

    @Test
    void admin_reset_password_for_unlocked_user_records_unlockedFromAutoLockout_false() throws Exception {
        AppUser user = appUserRepository.findByUsername("sarah.user").orElseThrow();

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", user.getId())
                        .with(jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN")))
                .andExpect(status().isOk());

        AppUserAuditEvent auditEvent = appUserAuditEventRepository
                .findTopByUser_IdOrderByCreatedAtDesc(user.getId())
                .orElseThrow();
        assertFalse(auditEvent.getAfterStateJson().get("unlockedFromAutoLockout").asBoolean());
        assertFalse(auditEvent.getAfterStateJson().has("priorLockReason"));
    }

    private AppUser lockUserViaBruteForce() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            attemptLogin("sarah.user", "WrongPassword!", CLIENT_IP)
                    .andExpect(status().isUnauthorized());
        }
        alertRuleEvaluationWorker.evaluateScheduledRules();
        entityManager.clear();
        return appUserRepository.findByUsername("sarah.user").orElseThrow();
    }

    private long countLoginFailedEvents(String username) {
        return authEventAuditRepository.countByUsernameAndEventType(username, AuthEventType.LOGIN_FAILED);
    }

    private long countOpenAlerts(OpsAlertType type, java.util.UUID subjectId) {
        return opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == type
                        && subjectId.equals(alert.getSubjectId())
                        && alert.getStatus() == OpsAlertStatus.NEW)
                .count();
    }

    private LoginArtifacts login(String username, String password, String clientIp) throws Exception {
        MvcResult loginResult = attemptLogin(username, password, clientIp)
                .andExpect(status().isOk())
                .andReturn();

        JsonNode tokenResponse = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = tokenResponse.get("accessToken").asText();
        Cookie refreshCookie = loginResult.getResponse().getCookie("lms-refresh");
        assertNotNull(refreshCookie);
        return new LoginArtifacts(accessToken, refreshCookie);
    }

    private ResultActions attemptLogin(String username, String password, String clientIp) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        // Login authenticates by email; the seeded users use <username>@bhawana.local.
        body.put("email", username + "@bhawana.local");
        body.put("password", password);

        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    private record LoginArtifacts(String accessToken, Cookie refreshCookie) {
    }
}
