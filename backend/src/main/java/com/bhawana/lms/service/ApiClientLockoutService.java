package com.bhawana.lms.service;

import com.bhawana.lms.repo.ApiClientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brute-force throttle for API client credential checks.
 *
 * <p>A failed attempt is persisted in its own committed transaction ({@code REQUIRES_NEW}) so the
 * counter survives the rollback of the enclosing failed authentication — the same claim pattern used
 * for idempotency records. After {@link #MAX_FAILED_ATTEMPTS} consecutive failures the client is
 * throttled for {@link #LOCK_DURATION}; the window auto-expires, so no manual unlock is required.
 */
@Service
public class ApiClientLockoutService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final ApiClientRepository apiClientRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ApiClientLockoutService(
            ApiClientRepository apiClientRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.apiClientRepository = apiClientRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void registerFailedAttempt(UUID apiClientId, Instant now) {
        requiresNewTransactionTemplate.executeWithoutResult(status ->
                apiClientRepository.findById(apiClientId).ifPresent(client -> {
                    client.registerFailedAuth(now, MAX_FAILED_ATTEMPTS, LOCK_DURATION);
                    apiClientRepository.save(client);
                }));
    }
}
