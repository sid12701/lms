package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class BootstrapSyncPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private AppUserAuditEventRepository appUserAuditEventRepository;
    @Autowired private SecurityProperties securityProperties;

    @Test
    void systemAdminCanRestoreDeletedBootstrapUserWithDurableAudit() throws Exception {
        String username = securityProperties.getBootstrapUser().getUsername().trim().toLowerCase();
        TenantScopedExecution.runAsAdmin(() -> {
            appUserRepository.findByUsername(username).ifPresent(user -> {
                appUserAuditEventRepository.deleteAll();
                appUserRepository.delete(user);
            });
        });

        mockMvc.perform(post("/api/v1/internal/system/bootstrap-sync")
                        .with(jwt()
                                .jwt(token -> token.subject("recovery.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN"))
                        .header("X-Correlation-Id", "bootstrap-sync-test"))
                .andExpect(status().isNoContent());

        TenantScopedExecution.runAsAdmin(() -> {
            var restored = appUserRepository.findByUsername(username).orElseThrow();
            assertTrue(restored.getRoles().stream().anyMatch(role -> role.getCode().name().equals("SYSTEM_ADMIN")));
            var audit = appUserAuditEventRepository.findTopByUser_IdOrderByCreatedAtDesc(restored.getId()).orElseThrow();
            assertEquals("recovery.admin", audit.getActorUsername());
            assertEquals("RESTORED", audit.getAfterStateJson().get("result").asText());
        });
    }

    @Test
    void nonAdminCannotRunBootstrapSync() throws Exception {
        mockMvc.perform(post("/api/v1/internal/system/bootstrap-sync")
                        .with(jwt()
                                .jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER")))
                .andExpect(status().isForbidden());
    }
}
