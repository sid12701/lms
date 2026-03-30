package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class UserAdminControllerTest {

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
    void systemAdminCanResetManagedUserPassword() throws Exception {
        AppUser managedUser = appUserRepository.findByUsernameIgnoreCase("test.user").orElseThrow();
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

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("test.user", temporaryPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void nonSystemAdminCannotResetManagedUserPassword() throws Exception {
        AppUser managedUser = appUserRepository.findByUsernameIgnoreCase("test.user").orElseThrow();

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", managedUser.getId())
                        .with(opsUser()))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
