package com.bhawana.lms.tenant;

/**
 * How the tenant connection pool establishes the restricted tenant database role.
 * <ul>
 *   <li>{@link #DIRECT_LOGIN} — the pool authenticates as {@code lms_tenant_app} directly.</li>
 *   <li>{@link #ASSUME_ROLE} — the pool authenticates with the admin login and issues
 *       transaction-scoped {@code SET LOCAL ROLE} on each checkout (PgBouncer transaction mode).</li>
 * </ul>
 */
public enum TenantConnectionStrategy {
    DIRECT_LOGIN,
    ASSUME_ROLE
}
