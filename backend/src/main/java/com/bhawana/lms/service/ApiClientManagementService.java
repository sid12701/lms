package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientAuditEvent;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ApiClientAuditEventRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiClientManagementService {

    public static final int DEFAULT_ROTATE_GRACE_SECONDS = 300;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository apiClientRepository;
    private final ApiClientAuditEventRepository apiClientAuditEventRepository;
    private final LspRepository lspRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public ApiClientManagementService(
            ApiClientRepository apiClientRepository,
            ApiClientAuditEventRepository apiClientAuditEventRepository,
            LspRepository lspRepository,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper
    ) {
        this.apiClientRepository = apiClientRepository;
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
            ApiClientStatus status,
            String actorUsername,
            String actorIp
    ) {
        ApiClientStatus effectiveStatus = status == null ? ApiClientStatus.ACTIVE : status;
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown LSP id: " + lspId));

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
        recordAudit(
                saved,
                actorUsername,
                "CLIENT_CREATED",
                Map.of("snapshot", auditSnapshot(saved)),
                actorIp
        );
        return new CreatedApiClient(saved, clientSecret);
    }

    @Transactional(readOnly = true)
    public List<ApiClientView> listClients() {
        return apiClientRepository.findAll().stream()
                .sorted(java.util.Comparator
                        .comparing(ApiClient::getCreatedAt)
                        .reversed()
                        .thenComparing(ApiClient::getClientId))
                .map(ApiClientView::new)
                .toList();
    }

    @Transactional
    public ApiClientView updateClient(
            UUID id,
            String actorUsername,
            String actorIp,
            String name,
            String description,
            ApiClientStatus status
    ) {
        ApiClient client = apiClientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown API client id: " + id));

        Map<String, Object> before = auditSnapshot(client);

        client.updateManagedProfile(
                name,
                description == null ? null : normalizeDescription(description),
                status
        );
        ApiClient saved = apiClientRepository.save(client);

        recordAudit(
                saved,
                actorUsername,
                resolveUpdateAction(before, auditSnapshot(saved)),
                Map.of("before", before, "after", auditSnapshot(saved)),
                actorIp
        );

        return new ApiClientView(saved);
    }

    @Transactional
    public RotatedApiClient rotateSecret(
            UUID id,
            String actorUsername,
            String actorIp,
            Integer graceSeconds
    ) {
        ApiClient client = apiClientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown API client id: " + id));

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
        recordAudit(saved, actorUsername, "SECRET_ROTATED", rotateDetails, actorIp);

        return new RotatedApiClient(new ApiClientView(saved), newSecret, previousValidUntil);
    }

    private static String resolveUpdateAction(Map<String, Object> before, Map<String, Object> after) {
        String beforeStatus = String.valueOf(before.get("status"));
        String afterStatus = String.valueOf(after.get("status"));
        if ("ACTIVE".equals(beforeStatus) && "INACTIVE".equals(afterStatus)) {
            return "CLIENT_DISABLED";
        }
        if ("INACTIVE".equals(beforeStatus) && "ACTIVE".equals(afterStatus)) {
            return "CLIENT_ENABLED";
        }
        return "CLIENT_UPDATED";
    }

    private Map<String, Object> auditSnapshot(ApiClient client) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("clientId", client.getClientId());
        snapshot.put("name", client.getName());
        snapshot.put("description", client.getDescription());
        snapshot.put("status", client.getStatus().name());
        snapshot.put("lspId", client.getLsp().getId().toString());
        return snapshot;
    }

    private void recordAudit(
            ApiClient client,
            String actorUsername,
            String action,
            Map<String, Object> details,
            String actorIp
    ) {
        try {
            apiClientAuditEventRepository.save(new ApiClientAuditEvent(
                    client,
                    actorUsername,
                    action,
                    objectMapper.writeValueAsString(details),
                    CorrelationIdHolder.get(),
                    actorIp
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

    public record ApiClientView(ApiClient client) {
    }

    public record CreatedApiClient(ApiClient client, String rawSecret) {
    }

    public record RotatedApiClient(ApiClientView clientView, String rawSecret, Instant oldSecretValidUntil) {
    }
}
