package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/system")
@SecurityRequirement(name = "bearerAuth")
public class SystemController {

    private final Environment environment;
    private final AppUserRepository appUserRepository;

    public SystemController(Environment environment, AppUserRepository appUserRepository) {
        this.environment = environment;
        this.appUserRepository = appUserRepository;
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

        UUID userId = appUserRepository.findByUsername(authentication.getName())
                .map(AppUser::getId)
                .orElseGet(() -> deterministicBootstrapId(authentication.getName()));

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
     * Stable UUID for the in-memory bootstrap admin used when no app_user row exists
     * (test/profile edge cases). Hash-based so it's deterministic across requests.
     */
    private static UUID deterministicBootstrapId(String username) {
        return UUID.nameUUIDFromBytes(("lms-bootstrap:" + username).getBytes(StandardCharsets.UTF_8));
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
