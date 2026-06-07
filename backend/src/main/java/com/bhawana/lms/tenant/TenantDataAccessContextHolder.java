package com.bhawana.lms.tenant;

import java.util.UUID;

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

    private static HolderContext requireContext() {
        HolderContext context = CONTEXT.get();
        if (context == null) {
            throw new MissingTenantContextException(
                    "Tenant data-access context is not set on this thread. "
                            + "Call useAdmin(), useTenant(lspId), or TenantScopedExecution.callAsAdmin/callAsTenant "
                            + "before accessing tenant-scoped data."
            );
        }
        return context;
    }

    private record HolderContext(TenantDataAccessMode mode, UUID lspId) {
    }
}
