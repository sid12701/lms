package com.bhawana.lms.web;

import static org.hamcrest.Matchers.hasItem;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditExplorerControllerAppUserStreamTest {

    private static final String CLIENT_IP = "203.0.113.50";

    @Autowired
    private MockMvc mockMvc;

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
    void appUserStreamSurfacesPasswordResetAuditRow() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();

        mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", managedUser.getId())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "APP_USER")
                        .queryParam("actorUsername", "ops.admin")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].stream").value("APP_USER"))
                .andExpect(jsonPath("$.items[0].action").value("PASSWORD_RESET_BY_ADMIN"))
                .andExpect(jsonPath("$.items[0].detail.userId").value(managedUser.getId().toString()))
                .andExpect(jsonPath("$.items[0].correlationId").isString());
    }

    @Test
    void appUserStreamSurfacesUserUpdatedRowWithoutEventType() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();

        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", managedUser.getId())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"explorer-app-user@bhawana.local\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "APP_USER")
                        .queryParam("actorUsername", "ops.admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].stream").value("APP_USER"))
                .andExpect(jsonPath("$.items[0].action").value("USER_UPDATED"))
                .andExpect(jsonPath("$.items[0].detail.userId").value(managedUser.getId().toString()));
    }

    @Test
    void streamsFilterExcludesApplicationRows() throws Exception {
        AppUser managedUser = appUserRepository.findByUsername("test.user").orElseThrow();
        mockMvc.perform(put("/api/v1/internal/admin/users/{userId}", managedUser.getId())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"streams-filter@bhawana.local\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "APP_USER,API_CLIENT")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].stream", hasItem("APP_USER")))
                .andExpect(jsonPath("$.items[*].stream").exists());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }
}
