package com.bhawana.lms.tenant;

import java.util.UUID;

/**
 * Immutable snapshot of the current thread's tenant data-access scope.
 */
public record TenantAccessContext(TenantDataAccessMode mode, UUID lspId) {
}
