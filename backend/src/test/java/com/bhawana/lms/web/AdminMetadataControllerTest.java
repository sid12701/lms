package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminMetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void systemAdminCanReadAdminMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/metadata").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleCodes[0]").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.roleCodes[1]").value("OPS_USER"))
                .andExpect(jsonPath("$.roleCodes[2]").value("PRODUCT_ADMIN"))
                .andExpect(jsonPath("$.roleCodes[3]").value("LSP_UI_READ"))
                .andExpect(jsonPath("$.roleCodes[4]").value("LSP_UI_WRITE"))
                .andExpect(jsonPath("$.roleCodes[5]").value("LSP_API_CLIENT"))
                .andExpect(jsonPath("$.lspStatuses[0]").value("ACTIVE"))
                .andExpect(jsonPath("$.lspStatuses[1]").value("INACTIVE"))
                .andExpect(jsonPath("$.userStatuses[0]").value("ACTIVE"))
                .andExpect(jsonPath("$.userStatuses[1]").value("INACTIVE"))
                .andExpect(jsonPath("$.apiClientStatuses[0]").value("ACTIVE"))
                .andExpect(jsonPath("$.apiClientStatuses[1]").value("INACTIVE"));
    }

    @Test
    void nonSystemAdminCannotReadAdminMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/metadata").with(opsUser()))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", java.util.List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", java.util.List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
