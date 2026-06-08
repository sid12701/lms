package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.common.correlation.CorrelationIdFilter;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthEventAuditRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.service.AdminDirectoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AuthControllerAuthAuditTest {

    private static final String CLIENT_IP = "203.0.113.77";
    private static final String CORRELATION_ID = "auth-audit-test-corr-71";

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private ApiClientManagementService apiClientManagementService;

    @Autowired
    private AdminDirectoryService adminDirectoryService;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        authEventAuditRepository.deleteAllInBatch();

        AppRole opsUserRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();

        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseGet(() ->
                appUserRepository.save(new AppUser(
                        "test.user",
                        "test.user@bhawana.local",
                        passwordEncoder.encode("TestPassword123!"),
                        UserStatus.ACTIVE,
                        null,
                        Set.of(opsUserRole)
                )));
        managedUser.changePassword(passwordEncoder.encode("TestPassword123!"));
        appUserRepository.save(managedUser);
    }

    @Test
    void loginSucceededWritesCompleteAuthAuditRow() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .header("X-Forwarded-For", CLIENT_IP)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "TestPassword123!"))))
                .andExpect(status().isOk());

        AppUser user = appUserRepository.findByUsername("test.user").orElseThrow();

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("username", "test.user")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].eventType").value("LOGIN_SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].username").value("test.user"))
                .andExpect(jsonPath("$.items[0].userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.items[0].apiClientId").doesNotExist())
                .andExpect(jsonPath("$.items[0].failureReason").doesNotExist())
                .andExpect(jsonPath("$.items[0].actorIp").value(CLIENT_IP))
                .andExpect(jsonPath("$.items[0].correlationId").value(CORRELATION_ID));
    }

    @Test
    void loginFailedWritesInvalidCredentialsReason() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .header("X-Forwarded-For", CLIENT_IP)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "WrongPassword123!"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("username", "test.user")
                        .queryParam("eventType", "LOGIN_FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.items[0].failureReason").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginFailedForInactiveUserWritesAccountDisabledReason() throws Exception {
        AppRole opsUserRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseThrow();
        appUserRepository.save(new AppUser(
                "inactive.user",
                "inactive.user@bhawana.local",
                passwordEncoder.encode("TestPassword123!"),
                UserStatus.INACTIVE,
                null,
                Set.of(opsUserRole)
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("inactive.user", "TestPassword123!"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("username", "inactive.user")
                        .queryParam("eventType", "LOGIN_FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].failureReason").value("ACCOUNT_DISABLED"));
    }

    @Test
    void apiClientTokenSucceededAndFailedAreAudited() throws Exception {
        Lsp lsp = lspRepository.save(new Lsp("AUDIT-API", "Audit API LSP", LspStatus.ACTIVE));
        ApiClientManagementService.CreatedApiClient created = apiClientManagementService.createClient(
                "Audit API Client",
                null,
                lsp.getId(),
                com.bhawana.lms.domain.ApiClientStatus.ACTIVE,
                "test.setup",
                null
        );

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .header("X-Forwarded-For", CLIENT_IP)
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                created.client().getClientId(),
                                created.rawSecret()
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("username", created.client().getClientId())
                        .queryParam("eventType", "API_CLIENT_TOKEN_SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].apiClientId").value(created.client().getId().toString()))
                .andExpect(jsonPath("$.items[0].userId").doesNotExist());

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER_NAME, "bad-secret-corr")
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                created.client().getClientId(),
                                "not-the-secret"
                        ))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("username", created.client().getClientId())
                        .queryParam("eventType", "API_CLIENT_TOKEN_FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].failureReason").value("INVALID_CREDENTIALS"));
    }

    @Test
    void tokenRefreshSuccessAndFailuresAreAudited() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .header("X-Forwarded-For", CLIENT_IP)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "TestPassword123!"))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("lms-refresh");
        org.junit.jupiter.api.Assertions.assertNotNull(refreshCookie);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie)
                        .header(CorrelationIdFilter.HEADER_NAME, "refresh-success-corr")
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("username", "test.user")
                        .queryParam("eventType", "TOKEN_REFRESH_SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("TOKEN_REFRESH_SUCCEEDED"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(CorrelationIdFilter.HEADER_NAME, "missing-cookie-corr"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("eventType", "TOKEN_REFRESH_FAILED")
                        .queryParam("username", "<anonymous>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].failureReason").value("MISSING_REFRESH_COOKIE"));
    }

    @Test
    void logoutWritesAuditRowEvenWithoutRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("eventType", "LOGOUT")
                        .queryParam("username", "<anonymous>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("LOGOUT"));
    }

    @Test
    void passwordChangedWritesAuditRow() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        AdminDirectoryService.ResetPasswordResult resetResult = adminDirectoryService.resetUserPassword(
                managedUser.getId(),
                "ops.admin",
                CLIENT_IP,
                CORRELATION_ID
        );

        entityManager.clear();

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", resetResult.temporaryPassword()))))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .header(CorrelationIdFilter.HEADER_NAME, "password-change-corr")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.ChangePasswordRequest("BrandNewPassword123!"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(systemAdmin())
                        .queryParam("username", "test.user")
                        .queryParam("eventType", "PASSWORD_CHANGED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$.items[0].actorIp").value(CLIENT_IP));
    }

    @Test
    void opsUserCannotReadAuthAuditEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/internal/ops/auth-audit")
                        .with(jwt().jwt(token -> token.subject("test.user").claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER")))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }
}
