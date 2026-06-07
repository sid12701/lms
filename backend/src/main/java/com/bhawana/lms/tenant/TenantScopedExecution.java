package com.bhawana.lms.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs work under an explicit tenant or admin data-access scope and always restores the prior scope.
 * <p>
 * ThreadLocal scope does not propagate to {@code @Async} tasks or other threads — call these helpers
 * inside the Runnable/Supplier that runs on the target thread.
 */
public final class TenantScopedExecution {

    private TenantScopedExecution() {
    }

    public static void runAsAdmin(Runnable task) {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useAdmin();
        try {
            task.run();
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }

    public static void runAsTenant(UUID lspId, Runnable task) {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useTenant(lspId);
        try {
            task.run();
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }

    public static <T> T callAsAdmin(Supplier<T> task) {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useAdmin();
        try {
            return task.get();
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }

    public static <T> T callAsTenant(UUID lspId, Supplier<T> task) {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useTenant(lspId);
        try {
            return task.get();
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }
}
