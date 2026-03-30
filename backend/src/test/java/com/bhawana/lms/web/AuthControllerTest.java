package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.service.AdminDirectoryService;
import com.bhawana.lms.repo.AppRoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    @BeforeEach
    void setUpManagedUser() {
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
    void tokenEndpointReturnsJwtForBootstrapUser() throws Exception {
        AuthController.LoginRequest loginRequest = new AuthController.LoginRequest("test.admin", "TestPassword123!");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test.admin"))
                .andExpect(jsonPath("$.roles[0]").value("SYSTEM_ADMIN"));
    }

    @Test
    void tokenEndpointReturnsJwtForManagedUser() throws Exception {
        AuthController.LoginRequest loginRequest = new AuthController.LoginRequest("test.user", "TestPassword123!");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test.user"))
                .andExpect(jsonPath("$.roles[0]").value("OPS_USER"));
    }

    @Test
    void refreshEndpointMintsFreshTokenForBearerPrincipal() throws Exception {
        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", "TestPassword123!"))))
                .andExpect(status().isOk())
                .andReturn();

        String originalToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("accessToken")
                .asText();

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + originalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
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
        AppUser managedUser = appUserRepository.findByUsernameIgnoreCase("test.user").orElseThrow();
        String oldPasswordHash = managedUser.getPasswordHash();

        AdminDirectoryService.ResetPasswordResult resetResult = adminDirectoryService.resetUserPassword(managedUser.getId());

        assertNotEquals(oldPasswordHash, appUserRepository.findById(managedUser.getId()).orElseThrow().getPasswordHash());

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", resetResult.temporaryPassword()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
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
}
