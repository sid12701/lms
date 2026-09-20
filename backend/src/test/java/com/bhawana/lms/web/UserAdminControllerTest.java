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
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.bhawana.lms.support.IpTestSupport;
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

    @Autowired
    private EntityManager entityManager;

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
    void listUsersIncludesLockoutFieldsWhenUserIsLocked() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        managedUser.lockForBruteForce(Instant.parse("2026-06-08T10:00:00Z"));
        appUserRepository.save(managedUser);

        mockMvc.perform(get("/api/v1/internal/admin/users").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='test.user')].lockedAt").isNotEmpty())
                .andExpect(jsonPath("$[?(@.username=='test.user')].lockReason").value(AppUser.LOCK_REASON_BRUTE_FORCE));
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

        entityManager.clear();
        assertNotEquals(oldPasswordHash, appUserRepository.findById(managedUser.getId()).orElseThrow().getPasswordHash());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.LoginRequest("test.user@bhawana.local", temporaryPassword))))
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

        entityManager.clear();
        assertTrue(appUserRepository.findById(managedUser.getId()).orElseThrow().getTokenVersion() > 0L);

        mockMvc.perform(get("/api/v1/internal/system/context")
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
                .andExpect(status().isUnprocessableEntity())
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
                .andExpect(status().isUnprocessableEntity())
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
    void newlyCreatedUserMustChangePasswordOnFirstLogin() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", "new.ops.user");
        body.put("email", "new.ops.user@bhawana.local");
        body.put("password", "TempPassword123!");
        body.set("roles", objectMapper.createArrayNode().add("OPS_USER"));

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.LoginRequest("new.ops.user@bhawana.local", "TempPassword123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthApiResponses.ChangePasswordRequest("NewPassword456!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));

        entityManager.clear();
        assertFalse(appUserRepository.findByUsername("new.ops.user").orElseThrow().isPasswordChangeRequired());
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
    void createUserRejectsLspRoleWithoutLspAssignment() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", "missing.lsp.user");
        body.put("email", "missing.lsp.user@bhawana.local");
        body.put("password", "TempPassword123!");
        body.set("roles", objectMapper.createArrayNode().add("LSP_UI_READ"));

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROLE_SCOPE_CONFLICT"))
                .andExpect(jsonPath("$.violations[0].field").value("lspId"))
                .andExpect(jsonPath("$.violations[0].message").value("required for LSP-scoped roles"));
    }

    @Test
    void createUserValidationUsesFriendlyMessages() throws Exception {
        // An absent password requests server-generated mode, so validation
        // only complains about username/email/roles here.
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", "");
        body.put("email", "not-an-email");
        body.set("roles", objectMapper.createArrayNode());

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.field=='username')].message")
                        .value(org.hamcrest.Matchers.hasItem("This field is required.")))
                .andExpect(jsonPath("$.violations[?(@.field=='roles')].message")
                        .value(org.hamcrest.Matchers.hasItem("This field is required.")))
                .andExpect(jsonPath("$.violations[?(@.field=='email')].message")
                        .value(org.hamcrest.Matchers.hasItem("Enter a valid email address.")));
    }

    @Test
    void adminResetPasswordWritesAuditRowWithExpectedShape() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        assertFalse(managedUser.isPasswordChangeRequired());

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", managedUser.getId())
                        .with(systemAdmin())
                        .with(IpTestSupport.remoteAddr(CLIENT_IP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString());

        entityManager.clear();
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
                        .with(IpTestSupport.remoteAddr(CLIENT_IP))
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
                        .with(IpTestSupport.remoteAddr(CLIENT_IP)))
                .andExpect(status().isOk())
                .andReturn();

        String temporaryPassword = objectMapper.readTree(resetResult.getResponse().getContentAsString())
                .get("temporaryPassword")
                .asText();

        entityManager.clear();
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
                .andExpect(status().isNotFound());

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
                .andExpect(status().isConflict());
    }

    @Test
    void listUsersIsBoundedAndEmitsPaginationHeaders() throws Exception {
        AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();
        appUserRepository.save(new AppUser(
                "second.user",
                "second.user@bhawana.local",
                passwordEncoder.encode("TestPassword123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(opsRole)
        ));

        // M20 — a requested page returns only that slice; the total count comes
        // from the headers, not from downloading every row.
        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("limit", "1")
                        .param("offset", "0")
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("second.user"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Total-Count", "2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Limit", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Offset", "0"));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("limit", "1")
                        .param("offset", "1")
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("test.user"));

        // Offset past the end returns an empty page, not an error.
        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("limit", "10")
                        .param("offset", "500")
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Total-Count", "2"));
    }

    @Test
    void listUsersAppliesFiltersBeforePagination() throws Exception {
        AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();
        AppRole productRole = appRoleRepository.findByCodeIn(List.of(RoleCode.PRODUCT_ADMIN)).stream()
                .findFirst()
                .orElseThrow();

        // Multi-role user: visible under every granted role, not only the
        // highest-priority one the old client-side filter collapsed to.
        appUserRepository.save(new AppUser(
                "multi.role",
                "multi.role@bhawana.local",
                passwordEncoder.encode("TestPassword123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(opsRole, productRole)
        ));
        appUserRepository.save(new AppUser(
                "disabled.user",
                "disabled.user@bhawana.local",
                passwordEncoder.encode("TestPassword123!"),
                UserStatus.INACTIVE,
                null,
                Set.of(opsRole)
        ));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("role", "PRODUCT_ADMIN")
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("multi.role"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Total-Count", "1"));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("status", "INACTIVE")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("disabled.user"));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("q", "MULTI.ROLE")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("multi.role"));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("q", "disabled.user@bhawana")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("disabled.user"));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("q", "no-such-user")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listUsersRejectsInvalidPaginationParams() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("limit", "0")
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("offset", "-1")
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/internal/admin/users")
                        .param("limit", "201")
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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
        // Login authenticates by email; the seeded users use <username>@bhawana.local.
        body.put("email", username + "@bhawana.local");
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
