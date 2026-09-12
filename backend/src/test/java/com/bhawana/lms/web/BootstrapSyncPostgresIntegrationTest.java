package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserAuditEventRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.AuthPrincipalCache;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Autowired private AppRoleRepository appRoleRepository;
    @Autowired private AuthPrincipalCache authPrincipalCache;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SecurityProperties securityProperties;

    @Test
    void systemAdminCanRestoreDeletedBootstrapUserWithDurableAudit() throws Exception {
        String username = securityProperties.getBootstrapUser().getUsername().trim().toLowerCase();
        // The sync route requires a live managed actor, so the recovery admin is a real
        // row; a deleted subject cannot restore itself through this route.
        TenantScopedExecution.runAsAdmin(() -> {
            appUserRepository.findByUsername("recovery.admin").ifPresent(existing -> {
                appUserAuditEventRepository.deleteAll();
                appUserRepository.delete(existing);
            });
            AppRole adminRole = appRoleRepository.findByCodeIn(List.of(RoleCode.SYSTEM_ADMIN)).stream()
                    .findFirst().orElseThrow();
            appUserRepository.save(new AppUser(
                    "recovery.admin",
                    "recovery.admin@bhawana.local",
                    passwordEncoder.encode("RecoveryAdmin123!"),
                    UserStatus.ACTIVE,
                    null,
                    Set.of(adminRole)
            ));
            appUserRepository.findByUsername(username).ifPresent(user -> {
                appUserAuditEventRepository.deleteAll();
                appUserRepository.delete(user);
            });
            authPrincipalCache.evictAppUser("recovery.admin");
            authPrincipalCache.evictAppUser(username);
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
