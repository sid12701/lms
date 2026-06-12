package com.bhawana.lms.tenant;

import java.util.UUID;
import java.util.function.Supplier;

public final class TenantDataAccessContextHolder {

    private static final ThreadLocal<HolderContext> CONTEXT = new ThreadLocal<>();

    private TenantDataAccessContextHolder() {
    }

    public static void useAdmin() {
        CONTEXT.set(new HolderContext(TenantDataAccessMode.ADMIN, null));
    }

    public static void useTenant(UUID lspId) {
        CONTEXT.set(new HolderContext(TenantDataAccessMode.TENANT, lspId));
    }

    public static TenantDataAccessMode getMode() {
        return requireContext().mode();
    }

    public static UUID getCurrentLspId() {
        return requireContext().lspId();
    }

    /**
     * Returns the current scope, or {@code null} when none is set. Never throws.
     */
    public static TenantAccessContext snapshot() {
        HolderContext context = CONTEXT.get();
        return context == null ? null : new TenantAccessContext(context.mode(), context.lspId());
    }

    public static void restore(TenantAccessContext snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        if (snapshot.mode() == TenantDataAccessMode.TENANT) {
            useTenant(snapshot.lspId());
        } else {
            useAdmin();
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Runs {@code task} with admin data-access scope and restores the prior scope in a {@code finally}
     * block, including when {@code task} throws.
     */
    public static void runAsAdmin(Runnable task) {
        TenantScopedExecution.runAsAdmin(task);
    }

    /**
     * @see #runAsAdmin(Runnable)
     */
    public static <T> T runAsAdmin(Supplier<T> task) {
        return TenantScopedExecution.callAsAdmin(task);
    }

    private static HolderContext requireContext() {
        HolderContext context = CONTEXT.get();
        if (context == null) {
            throw new MissingTenantContextException(
                    "Tenant data-access context is not set on this thread. "
                            + "Call useAdmin(), useTenant(lspId), runAsAdmin(...), or TenantScopedExecution.callAsAdmin/callAsTenant "
                            + "before accessing tenant-scoped data."
            );
        }
        return context;
    }

    private record HolderContext(TenantDataAccessMode mode, UUID lspId) {
    }
}
