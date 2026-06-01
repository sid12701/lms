package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientAuditEvent;
import com.bhawana.lms.domain.ApiClientIpAllowlistEntry;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ApiClientAuditEventRepository;
import com.bhawana.lms.repo.ApiClientIpAllowlistRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiClientManagementService {

    public static final int DEFAULT_ROTATE_GRACE_SECONDS = 300;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository apiClientRepository;
    private final ApiClientIpAllowlistRepository apiClientIpAllowlistRepository;
    private final ApiClientAuditEventRepository apiClientAuditEventRepository;
    private final LspRepository lspRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public ApiClientManagementService(
            ApiClientRepository apiClientRepository,
            ApiClientIpAllowlistRepository apiClientIpAllowlistRepository,
            ApiClientAuditEventRepository apiClientAuditEventRepository,
            LspRepository lspRepository,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper
    ) {
        this.apiClientRepository = apiClientRepository;
        this.apiClientIpAllowlistRepository = apiClientIpAllowlistRepository;
        this.apiClientAuditEventRepository = apiClientAuditEventRepository;
        this.lspRepository = lspRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreatedApiClient createClient(
            String name,
            String description,
            UUID lspId,
            ApiClientStatus status
    ) {
        ApiClientStatus effectiveStatus = status == null ? ApiClientStatus.ACTIVE : status;
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        String clientId = generateClientId();
        while (apiClientRepository.existsByClientId(clientId)) {
            clientId = generateClientId();
        }

        String clientSecret = generateClientSecret();
        ApiClient apiClient = new ApiClient(
                clientId,
                lsp,
                name.trim(),
                normalizeDescription(description),
                passwordEncoder.encode(clientSecret),
                effectiveStatus
        );

        ApiClient saved = apiClientRepository.save(apiClient);
        return new CreatedApiClient(saved, clientSecret, List.of());
    }

    @Transactional(readOnly = true)
    public List<ApiClientView> listClients() {
        List<ApiClient> clients = apiClientRepository.findAll().stream()
                .sorted(java.util.Comparator
                        .comparing(ApiClient::getCreatedAt)
                        .reversed()
                        .thenComparing(ApiClient::getClientId))
                .toList();
        Map<UUID, List<String>> allowlistsByClientId = loadAllowlists(clients);
        return clients.stream()
                .map(client -> new ApiClientView(client, allowlistsByClientId.getOrDefault(client.getId(), List.of())))
                .toList();
    }

    @Transactional
    public ApiClientView updateClient(
            UUID id,
            String actorUsername,
            String name,
            String description,
            ApiClientStatus status,
            List<String> ipAllowlist
    ) {
        ApiClient client = apiClientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown API client id: " + id));

        Map<String, Object> before = auditSnapshot(client, loadAllowlist(client.getId()));

        client.updateManagedProfile(
                name,
                description == null ? null : normalizeDescription(description),
                status
        );
        ApiClient saved = apiClientRepository.save(client);

        List<String> resolvedAllowlist = loadAllowlist(saved.getId());
        if (ipAllowlist != null) {
            resolvedAllowlist = replaceAllowlist(saved, ipAllowlist);
        }

        recordAudit(
                saved,
                actorUsername,
                "CLIENT_UPDATED",
                Map.of("before", before, "after", auditSnapshot(saved, resolvedAllowlist))
        );

        return new ApiClientView(saved, resolvedAllowlist);
    }

    @Transactional
    public RotatedApiClient rotateSecret(UUID id, String actorUsername, Integer graceSeconds) {
        ApiClient client = apiClientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown API client id: " + id));

        int effectiveGraceSeconds = graceSeconds == null ? DEFAULT_ROTATE_GRACE_SECONDS : graceSeconds;
        if (effectiveGraceSeconds < 0) {
            throw new IllegalArgumentException("graceSeconds must be zero or positive.");
        }

        String newSecret = generateClientSecret();
        Instant now = Instant.now();
        Instant previousValidUntil = effectiveGraceSeconds == 0
                ? null
                : now.plusSeconds(effectiveGraceSeconds);
        String previousSecretHash = effectiveGraceSeconds == 0 ? null : client.getSecretHash();

        client.rotateSecret(
                passwordEncoder.encode(newSecret),
                previousSecretHash,
                previousValidUntil
        );
        if (client.getLsp().getStatus() == LspStatus.ACTIVE) {
            client.updateManagedProfile(null, null, ApiClientStatus.ACTIVE);
        }
        ApiClient saved = apiClientRepository.save(client);

        Map<String, Object> rotateDetails = new LinkedHashMap<>();
        rotateDetails.put("graceSeconds", effectiveGraceSeconds);
        if (previousValidUntil != null) {
            rotateDetails.put("oldSecretValidUntil", previousValidUntil.toString());
        }
        recordAudit(saved, actorUsername, "SECRET_ROTATED", rotateDetails);

        return new RotatedApiClient(
                new ApiClientView(saved, loadAllowlist(saved.getId())),
                newSecret,
                previousValidUntil
        );
    }

    @Transactional(readOnly = true)
    public List<String> loadAllowlist(UUID apiClientId) {
        return apiClientIpAllowlistRepository.findByApiClient_IdOrderByCidrAsc(apiClientId).stream()
                .map(ApiClientIpAllowlistEntry::getCidr)
                .toList();
    }

    private List<String> replaceAllowlist(ApiClient client, List<String> ipAllowlist) {
        List<String> normalized = normalizeAllowlist(ipAllowlist);
        apiClientIpAllowlistRepository.deleteByApiClient_Id(client.getId());
        for (String cidr : normalized) {
            apiClientIpAllowlistRepository.save(new ApiClientIpAllowlistEntry(client, cidr));
        }
        return normalized;
    }

    private Map<UUID, List<String>> loadAllowlists(List<ApiClient> clients) {
        if (clients.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = clients.stream().map(ApiClient::getId).toList();
        Map<UUID, List<String>> grouped = new LinkedHashMap<>();
        for (ApiClientIpAllowlistEntry entry : apiClientIpAllowlistRepository.findByApiClient_IdInOrderByCidrAsc(ids)) {
            grouped.computeIfAbsent(entry.getApiClient().getId(), ignored -> new ArrayList<>())
                    .add(entry.getCidr());
        }
        return grouped;
    }

    private static List<String> normalizeAllowlist(List<String> ipAllowlist) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String cidr : ipAllowlist) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            String trimmed = cidr.trim();
            try {
                new IpAddressMatcher(trimmed);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr);
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private Map<String, Object> auditSnapshot(ApiClient client, List<String> allowlist) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("clientId", client.getClientId());
        snapshot.put("name", client.getName());
        snapshot.put("description", client.getDescription());
        snapshot.put("status", client.getStatus().name());
        snapshot.put("lspId", client.getLsp().getId().toString());
        snapshot.put("ipAllowlist", allowlist);
        return snapshot;
    }

    private void recordAudit(ApiClient client, String actorUsername, String action, Map<String, Object> details) {
        try {
            apiClientAuditEventRepository.save(new ApiClientAuditEvent(
                    client,
                    actorUsername,
                    action,
                    objectMapper.writeValueAsString(details),
                    CorrelationIdHolder.get()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize API client audit details.", exception);
        }
    }

    private static String generateClientId() {
        return "cli_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateClientSecret() {
        byte[] secretBytes = new byte[32];
        SECURE_RANDOM.nextBytes(secretBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }

        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ApiClientView(ApiClient client, List<String> ipAllowlist) {
    }

    public record CreatedApiClient(ApiClient client, String rawSecret, List<String> ipAllowlist) {
    }

    public record RotatedApiClient(ApiClientView clientView, String rawSecret, Instant oldSecretValidUntil) {
    }
}
