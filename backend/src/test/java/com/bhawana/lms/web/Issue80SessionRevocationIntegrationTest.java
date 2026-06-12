package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class Issue80SessionRevocationIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.80";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppRoleRepository appRoleRepository;

    @Autowired
    private AuthEventAuditRepository authEventAuditRepository;

    @Autowired
    private AppUserAuditEventRepository appUserAuditEventRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUpUsers() {
        authEventAuditRepository.deleteAll();
        appUserAuditEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        appUserRepository.deleteAll();

        AppRole opsUserRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();
        AppRole productAdminRole = appRoleRepository.findByCodeIn(List.of(RoleCode.PRODUCT_ADMIN)).stream()
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
        appUserRepository.save(new AppUser(
                "bob.user",
                "bob.user@bhawana.local",
                passwordEncoder.encode("BobPassword123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(productAdminRole)
        ));
    }

    @Test
    void adminRevokesTargetUserAndTargetsAccessJwtImmediately401s() throws Exception {
        AppUser sarah = appUserRepository.findByUsername("sarah.user").orElseThrow();
        LoginArtifacts sarahSession = login("sarah.user", "SarahPassword123!");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/revoke-sessions", sarah.getId())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Suspected compromise"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.newTokenVersion").value(sarah.getTokenVersion() + 1));

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + sarahSession.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRevokeAlsoRevokesRefreshTokens() throws Exception {
        AppUser sarah = appUserRepository.findByUsername("sarah.user").orElseThrow();
        LoginArtifacts sarahSession = login("sarah.user", "SarahPassword123!");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/revoke-sessions", sarah.getId())
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(sarahSession.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRevokeWritesSessionsRevokedAuditRowWithDetails() throws Exception {
        AppUser sarah = appUserRepository.findByUsername("sarah.user").orElseThrow();
        login("sarah.user", "SarahPassword123!");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/revoke-sessions", sarah.getId())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Suspected compromise"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .param("username", "sarah.user")
                        .param("eventType", "SESSIONS_REVOKED_BY_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("SESSIONS_REVOKED_BY_ADMIN"))
                .andExpect(jsonPath("$.items[0].details.source").value("ADMIN_EXPLICIT"))
                .andExpect(jsonPath("$.items[0].details.actorUsername").value("ops.admin"))
                .andExpect(jsonPath("$.items[0].details.reason").value("Suspected compromise"))
                .andExpect(jsonPath("$.items[0].details.refreshTokensRevoked").isNumber())
                .andExpect(jsonPath("$.items[0].actorIp").value(CLIENT_IP));
    }

    @Test
    void adminRevokeDoesNotAffectOtherUsers() throws Exception {
        AppUser sarah = appUserRepository.findByUsername("sarah.user").orElseThrow();
        LoginArtifacts sarahSession = login("sarah.user", "SarahPassword123!");
        LoginArtifacts bobSession = login("bob.user", "BobPassword123!");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/revoke-sessions", sarah.getId())
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + bobSession.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(bobSession.refreshCookie()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + sarahSession.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonSystemAdminCannotRevokeSessions() throws Exception {
        AppUser sarah = appUserRepository.findByUsername("sarah.user").orElseThrow();

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/revoke-sessions", sarah.getId())
                        .with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokeSessionsForUnknownUserReturnsNotFound() throws Exception {
        UUID unknownUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/revoke-sessions", unknownUserId)
                        .with(systemAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminResetPasswordNowAlsoRevokesSessions() throws Exception {
        AppUser sarah = appUserRepository.findByUsername("sarah.user").orElseThrow();
        LoginArtifacts sarahSession = login("sarah.user", "SarahPassword123!");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", sarah.getId())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString());

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + sarahSession.accessToken()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(sarahSession.refreshCookie()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .param("username", "sarah.user")
                        .param("eventType", "SESSIONS_REVOKED_BY_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].details.source").value("ADMIN_RESET_PASSWORD"));
    }

    @Test
    void roleChangeInvalidatesExistingAccessTokenAndRefreshToken() throws Exception {
        AppUser sarah = appUserRepository.findByUsername("sarah.user").orElseThrow();
        LoginArtifacts sarahSession = login("sarah.user", "SarahPassword123!");

        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", sarah.getId())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roles", List.of("PRODUCT_ADMIN")))))
                .andExpect(status().isOk());

        entityManager.clear();
        assertTrue(appUserRepository.findById(sarah.getId()).orElseThrow().getTokenVersion() > 0L);

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + sarahSession.accessToken()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(sarahSession.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    private LoginArtifacts login(String username, String password) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode tokenResponse = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = tokenResponse.get("accessToken").asText();
        Cookie refreshCookie = loginResult.getResponse().getCookie("lms-refresh");
        assert refreshCookie != null : "Login response must include lms-refresh cookie";
        assertNotEquals("", accessToken);
        return new LoginArtifacts(accessToken, refreshCookie);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private record LoginArtifacts(String accessToken, Cookie refreshCookie) {
    }
}
