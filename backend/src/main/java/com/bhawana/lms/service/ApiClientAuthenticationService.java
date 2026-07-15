package com.bhawana.lms.service;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiClientAuthenticationService {

    private final ApiClientRepository apiClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApiClientLockoutService apiClientLockoutService;

    public ApiClientAuthenticationService(
            ApiClientRepository apiClientRepository,
            PasswordEncoder passwordEncoder,
            ApiClientLockoutService apiClientLockoutService
    ) {
        this.apiClientRepository = apiClientRepository;
        this.passwordEncoder = passwordEncoder;
        this.apiClientLockoutService = apiClientLockoutService;
    }

    @Transactional
    public AuthenticatedApiClient authenticate(String clientId, String clientSecret) {
        String normalizedClientId = requireField(clientId, "clientId");
        String normalizedClientSecret = requireField(clientSecret, "clientSecret");
        Instant now = Instant.now();

        ApiClient apiClient = apiClientRepository.findByClientId(normalizedClientId)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        // Reject while throttled without consulting the secret, so repeated guesses cannot advance
        // the attempt window. The throttle auto-expires, so a legitimate client recovers on its own.
        if (apiClient.isAuthThrottled(now)) {
            throw new BadCredentialsException("Invalid credentials");
        }

        validateActive(apiClient);

        apiClient.clearExpiredPreviousSecret(now);
        if (!matchesAnyActiveSecret(apiClient, normalizedClientSecret)) {
            apiClientLockoutService.registerFailedAttempt(apiClient.getId(), now);
            throw new BadCredentialsException("Invalid credentials");
        }

        apiClient.registerSuccessfulAuth();
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
        ApiClient apiClient = apiClientRepository.findByClientId(clientId)
                .orElseThrow(() -> new BadCredentialsException("Unknown API client: " + clientId));
        validateActive(apiClient);
        return new AuthenticatedApiClient(
                apiClient.getClientId(),
                apiClient.getName(),
                apiClient.getLsp().getId(),
                apiClient.getLsp().getCode()
        );
    }

    private static void validateActive(ApiClient apiClient) {
        if (apiClient.getStatus() != ApiClientStatus.ACTIVE) {
            throw new BadCredentialsException("Invalid credentials");
        }
        Lsp lsp = apiClient.getLsp();
        if (lsp == null || lsp.getStatus() != LspStatus.ACTIVE) {
            throw new BadCredentialsException("Invalid credentials");
        }
    }

    private boolean matchesAnyActiveSecret(ApiClient apiClient, String clientSecret) {
        if (passwordEncoder.matches(clientSecret, apiClient.getSecretHash())) {
            return true;
        }

        String previousSecretHash = apiClient.getPreviousSecretHash();
        Instant previousSecretValidUntil = apiClient.getPreviousSecretValidUntil();
        if (previousSecretHash == null || previousSecretValidUntil == null) {
            return false;
        }

        Instant now = Instant.now();
        return now.isBefore(previousSecretValidUntil)
                && passwordEncoder.matches(clientSecret, previousSecretHash);
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
