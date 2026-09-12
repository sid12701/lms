package com.bhawana.lms.security;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Resolve a verified Entra app identity (trusted tenant + azp/appid) to an enabled local
 * api_client and authoritative ACTIVE LSP. Never trusts caller-supplied lspId or authType claims.
 */
@Service
public class EntraMachineIdentityMappingService {

    private final SecurityProperties securityProperties;
    private final ApiClientRepository apiClientRepository;

    public EntraMachineIdentityMappingService(
            SecurityProperties securityProperties,
            ApiClientRepository apiClientRepository
    ) {
        this.securityProperties = securityProperties;
        this.apiClientRepository = apiClientRepository;
    }

    public boolean isEntraMachineToken(Jwt jwt) {
        return securityProperties.getEntraMachineIdentity().matchesIssuer(jwt.getClaimAsString("iss"));
    }

    public Optional<ResolvedEntraMachinePrincipal> resolve(Jwt jwt) {
        if (!isEntraMachineToken(jwt)) {
            return Optional.empty();
        }
        SecurityProperties.EntraMachineIdentity config = securityProperties.getEntraMachineIdentity();
        String tenantId = jwt.getClaimAsString("tid");
        String clientId = firstNonBlank(jwt.getClaimAsString("azp"), jwt.getClaimAsString("appid"));
        if (!config.getTrustedTenantId().equalsIgnoreCase(tenantId) || clientId == null) {
            return Optional.empty();
        }
        SecurityProperties.EntraMachineIdentity.Mapping mapping = findMapping(tenantId, clientId).orElse(null);
        if (mapping == null || !mapping.isEnabled()) {
            return Optional.empty();
        }
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null) {
            return Optional.empty();
        }
        if (mapping.getNotBefore() != null && issuedAt.isBefore(mapping.getNotBefore())) {
            return Optional.empty();
        }
        if (mapping.getRevokedAt() != null) {
            return Optional.empty();
        }
        return TenantScopedExecution.callAsAdmin(() -> {
            ApiClient client = apiClientRepository.findById(mapping.getLocalApiClientId()).orElse(null);
            if (client == null
                    || client.getStatus() != ApiClientStatus.ACTIVE
                    || client.getLsp() == null
                    || client.getLsp().getStatus() != LspStatus.ACTIVE) {
                return Optional.empty();
            }
            Instant invalidatedAt = client.getCredentialsInvalidatedAt();
            if (invalidatedAt != null && !issuedAt.isAfter(invalidatedAt)) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedEntraMachinePrincipal(
                    client.getClientId(),
                    client.getId(),
                    client.getLsp().getId(),
                    client.getLsp().getCode(),
                    client.getName()));
        });
    }

    private Optional<SecurityProperties.EntraMachineIdentity.Mapping> findMapping(
            String tenantId,
            String clientId
    ) {
        List<SecurityProperties.EntraMachineIdentity.Mapping> mappings =
                securityProperties.getEntraMachineIdentity().getMappings();
        if (mappings == null) {
            return Optional.empty();
        }
        List<SecurityProperties.EntraMachineIdentity.Mapping> matches = mappings.stream()
                .filter(mapping -> mapping.getExternalTenantId() != null
                        && mapping.getExternalClientId() != null
                        && mapping.getLocalApiClientId() != null
                        && mapping.getExternalTenantId().equalsIgnoreCase(tenantId)
                        && mapping.getExternalClientId().equalsIgnoreCase(clientId))
                .toList();
        if (matches.size() != 1) {
            return Optional.empty();
        }
        long sameExternalIdCount = mappings.stream()
                .filter(mapping -> mapping.getExternalClientId() != null
                        && mapping.getExternalClientId().equalsIgnoreCase(clientId))
                .map(SecurityProperties.EntraMachineIdentity.Mapping::getLocalApiClientId)
                .distinct()
                .count();
        if (sameExternalIdCount > 1) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    public record ResolvedEntraMachinePrincipal(
            String localClientId,
            UUID localApiClientUuid,
            UUID authoritativeLspId,
            String lspCode,
            String clientName
    ) {
    }
}
