package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDirectoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void systemAdminCanCreateAndListLspsAndUsers() throws Exception {
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "APEX",
                                "name", "Apex Finance",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("APEX"))
                .andReturn();

        JsonNode lspJson = objectMapper.readTree(lspResult.getResponse().getContentAsString());
        String lspId = lspJson.get("id").asText();

        mockMvc.perform(get("/api/v1/internal/admin/lsps").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("APEX"));

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "apex.ops",
                                "email", "apex.ops@bhawana.local",
                                "password", "Secret123!",
                                "status", "ACTIVE",
                                "lspId", lspId,
                                "roles", List.of("LSP_UI_WRITE")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("apex.ops"))
                .andExpect(jsonPath("$.roles[0]").value("LSP_UI_WRITE"))
                .andExpect(jsonPath("$.lspName").value("Apex Finance"));

        mockMvc.perform(get("/api/v1/internal/admin/users").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("apex.ops"));
    }

    @Test
    void systemAdminCanResetManagedUserPassword() throws Exception {
        MvcResult userResult = mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "reset.user",
                                "email", "reset.user@bhawana.local",
                                "password", "Secret123!",
                                "status", "ACTIVE",
                                "roles", List.of("OPS_USER")
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode userJson = objectMapper.readTree(userResult.getResponse().getContentAsString());
        String userId = userJson.get("id").asText();

        mockMvc.perform(post("/api/v1/internal/admin/users/" + userId + "/reset-password")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.username").value("reset.user"))
                .andExpect(jsonPath("$.temporaryPassword").isString());
    }

    @Test
    void nonSystemAdminCannotAccessAdminDirectoryEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/lsps").with(opsUser()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/internal/admin/users/00000000-0000-0000-0000-000000000000/reset-password")
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
