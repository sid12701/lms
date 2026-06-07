package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AppUserAuditEvent;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class UserAdminControllerTest {

    private static final String CLIENT_IP = "203.0.113.50";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppRoleRepository appRoleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AppUserAuditEventRepository appUserAuditEventRepository;

    @BeforeEach
    void setUpManagedUser() {
        appUserAuditEventRepository.deleteAll();
        appUserRepository.deleteAll();

        AppRole opsUserRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();

        appUserRepository.save(new AppUser(
                "test.user",
                "test.user@bhawana.local",
                passwordEncoder.encode("TestPassword123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(opsUserRole)
        ));
    }

    @Test
    void systemAdminCanResetManagedUserPassword() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        String oldPasswordHash = managedUser.getPasswordHash();

        MvcResult resetResult = mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", managedUser.getId())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(managedUser.getId().toString()))
                .andExpect(jsonPath("$.username").value("test.user"))
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();

        String temporaryPassword = objectMapper.readTree(resetResult.getResponse().getContentAsString())
                .get("temporaryPassword")
                .asText();

        assertNotEquals(oldPasswordHash, appUserRepository.findById(managedUser.getId()).orElseThrow().getPasswordHash());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", temporaryPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void systemAdminCanUpdateManagedUserEmail() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();

        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", managedUser.getId())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "updated.user@bhawana.local"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("updated.user@bhawana.local"));

        assertEquals(
                "updated.user@bhawana.local",
                appUserRepository.findById(managedUser.getId()).orElseThrow().getEmail()
        );
        assertEquals(1, appUserAuditEventRepository.count());
    }

    @Test
    void roleChangeInvalidatesExistingAccessToken() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        String accessToken = loginAccessToken("test.user", "TestPassword123!");

        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", managedUser.getId())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roles", List.of("PRODUCT_ADMIN")))))
                .andExpect(status().isOk());

        assertTrue(appUserRepository.findById(managedUser.getId()).orElseThrow().getTokenVersion() > 0L);

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void systemAdminCannotDisableOwnAccount() throws Exception {
        AppRole systemAdminRole = appRoleRepository.findByCodeIn(List.of(RoleCode.SYSTEM_ADMIN)).stream()
                .findFirst()
                .orElseThrow();
        AppUser self = appUserRepository.save(new AppUser(
                "self.admin",
                "self.admin@bhawana.local",
                passwordEncoder.encode("SelfAdmin123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(systemAdminRole)
        ));

        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", self.getId())
                        .with(jwt().jwt(jwt -> jwt.subject("self.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot disable your own account."));
    }

    @Test
    void lastSystemAdminCannotRemoveOwnSystemAdminRole() throws Exception {
        AppRole systemAdminRole = appRoleRepository.findByCodeIn(List.of(RoleCode.SYSTEM_ADMIN)).stream()
                .findFirst()
                .orElseThrow();
        AppUser self = appUserRepository.save(new AppUser(
                "solo.admin",
                "solo.admin@bhawana.local",
                passwordEncoder.encode("SoloAdmin123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(systemAdminRole)
        ));

        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", self.getId())
                        .with(jwt().jwt(jwt -> jwt.subject("solo.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roles", List.of("OPS_USER")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "You cannot remove the SYSTEM_ADMIN role while you are the last active system administrator."
                ));
    }

    @Test
    void nonSystemAdminCannotResetManagedUserPassword() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", managedUser.getId())
                        .with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUserCanonicalisesMixedCaseUsernameAndEmailToLowercase() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", "Mixed.Case.User");
        body.put("email", "Mixed.Case@Example.COM");
        body.put("password", "TempPassword123!");
        body.set("roles", objectMapper.createArrayNode().add("OPS_USER"));

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("mixed.case.user"))
                .andExpect(jsonPath("$.email").value("mixed.case@example.com"));

        AppUser stored = appUserRepository.findByUsername("MIXED.CASE.USER").orElseThrow();
        assertEquals("mixed.case.user", stored.getUsername());
        assertEquals("mixed.case@example.com", stored.getEmail());
    }

    @Test
    void adminResetPasswordWritesAuditRowWithExpectedShape() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        assertFalse(managedUser.isPasswordChangeRequired());

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", managedUser.getId())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString());

        AppUserAuditEvent auditEvent = appUserAuditEventRepository
                .findTopByUser_IdOrderByCreatedAtDesc(managedUser.getId())
                .orElseThrow();

        assertEquals("ops.admin", auditEvent.getActorUsername());
        assertEquals(CLIENT_IP, auditEvent.getActorIp());
        assertNotNull(auditEvent.getCorrelationId());
        assertFalse(auditEvent.getCorrelationId().isBlank());
        assertEquals("false", auditEvent.getBeforeStateJson().get("passwordChangeRequired").asText());
        assertEquals("true", auditEvent.getAfterStateJson().get("passwordChangeRequired").asText());
        assertEquals("PASSWORD_RESET_BY_ADMIN", auditEvent.getAfterStateJson().get("eventType").asText());
        assertFalse(auditEvent.getBeforeStateJson().has("eventType"));
    }

    @Test
    void updateUserAuditRowIncludesActorIpWithoutEventType() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();

        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", managedUser.getId())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "updated.user@bhawana.local"))))
                .andExpect(status().isOk());

        AppUserAuditEvent auditEvent = appUserAuditEventRepository
                .findTopByUser_IdOrderByCreatedAtDesc(managedUser.getId())
                .orElseThrow();

        assertEquals(CLIENT_IP, auditEvent.getActorIp());
        assertFalse(auditEvent.getAfterStateJson().has("eventType"));
    }

    @Test
    void resetPasswordAuditRowDoesNotContainTemporaryPassword() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();

        MvcResult resetResult = mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", managedUser.getId())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk())
                .andReturn();

        String temporaryPassword = objectMapper.readTree(resetResult.getResponse().getContentAsString())
                .get("temporaryPassword")
                .asText();

        AppUserAuditEvent auditEvent = appUserAuditEventRepository
                .findTopByUser_IdOrderByCreatedAtDesc(managedUser.getId())
                .orElseThrow();

        String beforeJson = auditEvent.getBeforeStateJson().toString();
        String afterJson = auditEvent.getAfterStateJson().toString();
        assertFalse(beforeJson.contains(temporaryPassword));
        assertFalse(afterJson.contains(temporaryPassword));
    }

    @Test
    void resetPasswordForUnknownUserDoesNotWriteAuditRow() throws Exception {
        long beforeCount = appUserAuditEventRepository.count();
        UUID unknownUserId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", unknownUserId)
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest());

        assertEquals(beforeCount, appUserAuditEventRepository.count());
    }

    @Test
    void createUserRejectsDuplicateMixedCaseEmail() throws Exception {
        // setUp() already seeded test.user@bhawana.local in lowercase.
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", "Different.User");
        body.put("email", "TEST.USER@bhawana.LOCAL");  // different case, same canonical email
        body.put("password", "TempPassword123!");
        body.set("roles", objectMapper.createArrayNode().add("OPS_USER"));

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private String loginAccessToken(String username, String password) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode tokenResponse = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return tokenResponse.get("accessToken").asText();
    }
}
