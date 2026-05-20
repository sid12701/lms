package com.bhawana.lms.tenant;

import java.util.UUID;

public final class TenantDataAccessContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantDataAccessContextHolder() {
    }

    public static void useAdmin() {
        CONTEXT.set(new TenantContext(TenantDataAccessMode.ADMIN, null));
    }

    public static void useTenant(UUID lspId) {
        CONTEXT.set(new TenantContext(TenantDataAccessMode.TENANT, lspId));
    }

    public static TenantDataAccessMode getMode() {
        TenantContext context = CONTEXT.get();
        return context == null ? TenantDataAccessMode.ADMIN : context.mode();
    }

    public static UUID getCurrentLspId() {
        TenantContext context = CONTEXT.get();
        return context == null ? null : context.lspId();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    private record TenantContext(TenantDataAccessMode mode, UUID lspId) {
    }
}
