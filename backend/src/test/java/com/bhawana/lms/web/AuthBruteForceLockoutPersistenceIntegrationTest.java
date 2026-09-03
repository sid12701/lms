package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.bhawana.lms.service.AlertRuleEvaluationWorker;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AuthBruteForceLockoutPersistenceIntegrationTest {

    private static final String CLIENT_IP = "198.51.100.42";
    private static final AtomicBoolean LOCKOUT_SEEDED = new AtomicBoolean(false);
    private static UUID lockedUserId;

    @Autowired
    private MockMvc mockMvc;

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
    private AlertRuleEvaluationWorker alertRuleEvaluationWorker;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpUsers() {
        if (LOCKOUT_SEEDED.get()) {
            return;
        }
        authEventAuditRepository.deleteAll();
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
    @Order(1)
    void seeds_locked_user_before_context_restart() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            attemptLogin("sarah.user", "WrongPassword!", CLIENT_IP)
                    .andExpect(status().isUnauthorized());
        }
        alertRuleEvaluationWorker.evaluateScheduledRules();

        AppUser lockedUser = appUserRepository.findByUsername("sarah.user").orElseThrow();
        assertNotNull(lockedUser.getLockedAt());
        lockedUserId = lockedUser.getId();
        LOCKOUT_SEEDED.set(true);
    }

    @Test
    @Order(2)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void lockout_persists_across_application_restart() throws Exception {
        AppUser reloaded = appUserRepository.findById(lockedUserId).orElseThrow();
        assertNotNull(reloaded.getLockedAt());

        attemptLogin("sarah.user", "SarahPassword123!", CLIENT_IP)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private org.springframework.test.web.servlet.ResultActions attemptLogin(
            String username,
            String password,
            String clientIp
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        // Login authenticates by email; the seeded users use <username>@bhawana.local.
        body.put("email", username + "@bhawana.local");
        body.put("password", password);

        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }
}
