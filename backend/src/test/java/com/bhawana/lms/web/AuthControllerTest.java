package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.ApiClientAuditEventRepository;
import com.bhawana.lms.repo.ApiClientIpAllowlistRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.service.AdminDirectoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class AuthControllerTest {

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
    private AdminDirectoryService adminDirectoryService;

    @Autowired
    private ApiClientManagementService apiClientManagementService;

    @Autowired
    private ApiClientAuditEventRepository apiClientAuditEventRepository;

    @Autowired
    private ApiClientIpAllowlistRepository apiClientIpAllowlistRepository;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private LspAuditEventRepository lspAuditEventRepository;

    @BeforeEach
    void setUpManagedUser() {
        appUserRepository.deleteAll();
        apiClientIpAllowlistRepository.deleteAll();
        apiClientAuditEventRepository.deleteAll();
        apiClientRepository.deleteAll();
        lspAuditEventRepository.deleteAllInBatch();
        lspRepository.deleteAll();

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
    void loginEndpointReturnsJwtForBootstrapUser() throws Exception {
        AuthController.LoginRequest loginRequest = new AuthController.LoginRequest("test.admin", "TestPassword123!");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
                .andExpect(jsonPath("$.username").value("test.admin"))
                .andExpect(jsonPath("$.roles[0]").value("SYSTEM_ADMIN"));
    }

    @Test
    void loginEndpointReturnsJwtForManagedUser() throws Exception {
        AuthController.LoginRequest loginRequest = new AuthController.LoginRequest("test.user", "TestPassword123!");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(managedUser.getId().toString()))
                .andExpect(jsonPath("$.username").value("test.user"))
                .andExpect(jsonPath("$.roles[0]").value("OPS_USER"));
    }

    @Test
    void refreshEndpointMintsFreshTokenFromCookie() throws Exception {
        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "TestPassword123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();

        String originalToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        Cookie refreshCookie = tokenResult.getResponse().getCookie("lms-refresh");
        assert refreshCookie != null : "Login response must include lms-refresh cookie";

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();

        String refreshedToken = objectMapper.readTree(refreshResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        assertNotEquals(originalToken, refreshedToken);

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + refreshedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test.user"))
                .andExpect(jsonPath("$.roles[0]").value("OPS_USER"));
    }

    @Test
    void managedUserPasswordResetAllowsNewLogin() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        String oldPasswordHash = managedUser.getPasswordHash();

        AdminDirectoryService.ResetPasswordResult resetResult = adminDirectoryService.resetUserPassword(managedUser.getId());

        assertNotEquals(oldPasswordHash, appUserRepository.findById(managedUser.getId()).orElseThrow().getPasswordHash());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", resetResult.temporaryPassword()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void managedUserMustChangePasswordAfterAdminReset() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        AdminDirectoryService.ResetPasswordResult resetResult = adminDirectoryService.resetUserPassword(managedUser.getId());

        MvcResult resetLoginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", resetResult.temporaryPassword()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn();

        String resetToken = objectMapper.readTree(resetLoginResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
        Cookie resetRefreshCookie = resetLoginResult.getResponse().getCookie("lms-refresh");

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + resetToken))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.errorReason").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.errorSource").value("Password change is required before accessing internal routes"))
                .andExpect(jsonPath("$.errors[0].errorReason").value("PASSWORD_CHANGE_REQUIRED"));

        assert resetRefreshCookie != null : "Login response must include lms-refresh cookie";
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(resetRefreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        MvcResult changePasswordResult = mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + resetToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.ChangePasswordRequest("NewPassword456!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();

        String changedToken = objectMapper.readTree(changePasswordResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "NewPassword456!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + changedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test.user"));

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + resetToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "TestPassword123!"))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("lms-refresh");
        assert refreshCookie != null;

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(refreshCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/v1/internal/system/context"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAcceptsJwtAuthorities() throws Exception {
        mockMvc.perform(get("/api/v1/internal/system/context")
                .with(jwt().jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                        .authorities(() -> "ROLE_OPS_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ops.user"))
                .andExpect(jsonPath("$.roles[0]").value("OPS_USER"));
    }

    @Test
    void tokenEndpointReturnsJwtForApiClientCredentials() throws Exception {
        Lsp lsp = lspRepository.save(new Lsp("APEX-PARTNER", "Apex Partner", LspStatus.ACTIVE));
        ApiClientManagementService.CreatedApiClient createdApiClient = apiClientManagementService.createClient(
                "Apex Machine Client",
                null,
                lsp.getId(),
                com.bhawana.lms.domain.ApiClientStatus.ACTIVE
        );

        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                createdApiClient.client().getClientId(),
                                createdApiClient.rawSecret()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void tenantUiUserReceivesLspContextInSystemSession() throws Exception {
        Lsp lsp = lspRepository.save(new Lsp("APEX-UI", "Apex UI Tenant", LspStatus.ACTIVE));
        AppRole lspUiReadRole = appRoleRepository.findByCodeIn(List.of(RoleCode.LSP_UI_READ)).stream()
                .findFirst()
                .orElseThrow();

        appUserRepository.save(new AppUser(
                "tenant.viewer",
                "tenant.viewer@bhawana.local",
                passwordEncoder.encode("TestPassword123!"),
                UserStatus.ACTIVE,
                lsp,
                Set.of(lspUiReadRole)
        ));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("tenant.viewer", "TestPassword123!"))))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("tenant.viewer"))
                .andExpect(jsonPath("$.roles[0]").value("LSP_UI_READ"))
                .andExpect(jsonPath("$.lspId").value(lsp.getId().toString()))
                .andExpect(jsonPath("$.lspName").value("Apex UI Tenant"));
    }

    @Test
    void apiClientRefreshCookieMintsFreshAccessToken() throws Exception {
        Lsp lsp = lspRepository.save(new Lsp("APEX-MACHINE", "Apex Machine Tenant", LspStatus.ACTIVE));
        ApiClientManagementService.CreatedApiClient created = apiClientManagementService.createClient(
                "Apex Machine Client",
                null,
                lsp.getId(),
                com.bhawana.lms.domain.ApiClientStatus.ACTIVE
        );

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                created.client().getClientId(),
                                created.rawSecret()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String originalToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("accessToken").asText();
        Cookie refreshCookie = tokenResult.getResponse().getCookie("lms-refresh");
        assert refreshCookie != null : "Token response must include lms-refresh cookie";

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        String refreshedToken = objectMapper.readTree(refreshResult.getResponse().getContentAsString())
                .get("accessToken").asText();
        assertNotEquals(originalToken, refreshedToken);

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + refreshedToken))
                .andExpect(status().isOk());
    }

    @Test
    void deletingManagedUserInvalidatesTheirRefreshTokens() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "TestPassword123!"))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("lms-refresh");
        assert refreshCookie != null : "Login response must include lms-refresh cookie";

        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        appUserRepository.deleteById(managedUser.getId());
        appUserRepository.flush();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }
}
