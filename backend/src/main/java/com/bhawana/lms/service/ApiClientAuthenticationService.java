package com.bhawana.lms.service;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiClientAuthenticationService {

    private final ApiClientRepository apiClientRepository;
    private final PasswordEncoder passwordEncoder;

    public ApiClientAuthenticationService(
            ApiClientRepository apiClientRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.apiClientRepository = apiClientRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthenticatedApiClient authenticate(String clientId, String clientSecret) {
        String normalizedClientId = requireField(clientId, "clientId");
        String normalizedClientSecret = requireField(clientSecret, "clientSecret");

        ApiClient apiClient = apiClientRepository.findByClientIdIgnoreCase(normalizedClientId)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (apiClient.getStatus() != ApiClientStatus.ACTIVE
                || !passwordEncoder.matches(normalizedClientSecret, apiClient.getSecretHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        apiClient.markUsed();
        ApiClient savedClient = apiClientRepository.save(apiClient);
        return new AuthenticatedApiClient(
                savedClient.getClientId(),
                savedClient.getName(),
                savedClient.getLsp().getId(),
                savedClient.getLsp().getCode()
        );
    }

    @Transactional(readOnly = true)
    public AuthenticatedApiClient lookupByClientId(String clientId) {
        ApiClient apiClient = apiClientRepository.findByClientIdIgnoreCase(clientId)
                .orElseThrow(() -> new BadCredentialsException("Unknown API client: " + clientId));
        return new AuthenticatedApiClient(
                apiClient.getClientId(),
                apiClient.getName(),
                apiClient.getLsp().getId(),
                apiClient.getLsp().getCode()
        );
    }

    private static String requireField(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    public record AuthenticatedApiClient(
            String clientId,
            String clientName,
            UUID lspId,
            String lspCode
    ) {
    }
}
