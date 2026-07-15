package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.service.LocalBootstrapAdminSyncService;
import com.bhawana.lms.service.SystemContextService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/system")
@SecurityRequirement(name = "bearerAuth")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final Environment environment;
    private final SystemContextService systemContextService;
    private final LocalBootstrapAdminSyncService localBootstrapAdminSyncService;

    public SystemController(
            Environment environment,
            SystemContextService systemContextService,
            LocalBootstrapAdminSyncService localBootstrapAdminSyncService
    ) {
        this.environment = environment;
        this.systemContextService = systemContextService;
        this.localBootstrapAdminSyncService = localBootstrapAdminSyncService;
    }

    @GetMapping("/context")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER','PRODUCT_ADMIN','LSP_UI_READ','LSP_UI_WRITE')")
    public SystemContextResponse context(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .toList();
        String lspId = null;
        String lspName = null;
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            lspId = jwt.getClaimAsString("lspId");
            lspName = jwt.getClaimAsString("lspName");
        }

        UUID userId = systemContextService.resolveUserId(authentication.getName());

        String[] activeProfiles = environment.getActiveProfiles();
        return new SystemContextResponse(
                environment.getProperty("spring.application.name"),
                activeProfiles.length == 0 ? List.of("default") : Arrays.asList(activeProfiles),
                userId,
                authentication.getName(),
                roles,
                CorrelationIdHolder.get(),
                lspId,
                lspName
        );
    }

    /**
     * Spec S10 — re-sync the configured bootstrap admin without a process restart.
     * Idempotent; audited durably and in the structured application log.
     */
    @PostMapping("/bootstrap-sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public void bootstrapSync(Authentication authentication) {
        log.info(
                "event=bootstrap_sync actor={} correlationId={}",
                authentication.getName(),
                CorrelationIdHolder.get()
        );
        localBootstrapAdminSyncService.syncBootstrapAdmin(
                authentication.getName(),
                CorrelationIdHolder.get()
        );
    }

    public record SystemContextResponse(
            String application,
            List<String> activeProfiles,
            UUID id,
            String username,
            List<String> roles,
            String correlationId,
            String lspId,
            String lspName
    ) {
    }
}
