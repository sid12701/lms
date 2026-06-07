package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantDataAccessMode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class InternalAdminTenantContextInterceptorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void internalOpsRequestClearsTenantContextAfterCompletion() throws Exception {
        TenantDataAccessContextHolder.clear();

        mockMvc.perform(get("/api/v1/internal/alerts")
                        .param("offset", "0")
                        .param("limit", "1")
                        .with(jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN")))
                .andExpect(status().isOk());

        assertEquals(TenantDataAccessMode.ADMIN, TenantDataAccessContextHolder.getMode());
    }

    @Test
    void authPasswordEndpointRestoresAdminTenantContextAfterCompletionInTestProfile() throws Exception {
        TenantDataAccessContextHolder.clear();

        mockMvc.perform(get("/api/v1/internal/admin/metadata")
                        .with(jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN")))
                .andExpect(status().isOk());

        assertEquals(TenantDataAccessMode.ADMIN, TenantDataAccessContextHolder.getMode());
    }
}
