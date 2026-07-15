package com.bhawana.lms.service;

import com.bhawana.lms.tenant.TenantAccessContext;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class AdminApiIdempotencyService {

    private final IdempotencyExecutionCoordinator idempotencyExecutionCoordinator;

    public AdminApiIdempotencyService(IdempotencyExecutionCoordinator idempotencyExecutionCoordinator) {
        this.idempotencyExecutionCoordinator = idempotencyExecutionCoordinator;
    }

    public <T> T execute(
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String normalizedKey = IdempotencyKeyValidator.requireUuidV4(idempotencyKey);
        return idempotencyExecutionCoordinator.executeAdmin(
                operationKey,
                normalizedKey,
                requestFingerprintSource,
                responseType,
                () -> runUnderAdminScope(action)
        );
    }

    private <T> T runUnderAdminScope(Supplier<T> action) {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useAdmin();
        try {
            return action.get();
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }
}
