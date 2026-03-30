package com.bhawana.lms.service;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.LspRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiClientManagementService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository apiClientRepository;
    private final LspRepository lspRepository;
    private final PasswordEncoder passwordEncoder;

    public ApiClientManagementService(
            ApiClientRepository apiClientRepository,
            LspRepository lspRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.apiClientRepository = apiClientRepository;
        this.lspRepository = lspRepository;
        this.passwordEncoder = passwordEncoder;
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
        while (apiClientRepository.existsByClientIdIgnoreCase(clientId)) {
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
        return new CreatedApiClient(saved, clientSecret);
    }

    @Transactional(readOnly = true)
    public List<ApiClient> listClients() {
        return apiClientRepository.findAll().stream()
                .sorted(java.util.Comparator
                        .comparing(ApiClient::getCreatedAt)
                        .reversed()
                        .thenComparing(ApiClient::getClientId))
                .toList();
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

    public record CreatedApiClient(ApiClient client, String rawSecret) {
    }
}
