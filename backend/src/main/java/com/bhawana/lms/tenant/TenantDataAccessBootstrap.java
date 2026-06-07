package com.bhawana.lms.tenant;

/**
 * Marks infrastructure initialization (e.g. Hibernate schema export) that may open JDBC
 * connections before HTTP/worker entry points set tenant scope. Not for application code.
 */
final class TenantDataAccessBootstrap {

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private TenantDataAccessBootstrap() {
    }

    static boolean isActive() {
        Integer depth = DEPTH.get();
        return depth != null && depth > 0;
    }

    static void enter() {
        Integer depth = DEPTH.get();
        DEPTH.set(depth == null ? 1 : depth + 1);
    }

    static void exit() {
        Integer depth = DEPTH.get();
        if (depth == null || depth <= 1) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth - 1);
        }
    }
}
