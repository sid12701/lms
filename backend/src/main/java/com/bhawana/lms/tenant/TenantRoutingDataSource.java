package com.bhawana.lms.tenant;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

final class TenantRoutingDataSource extends AbstractRoutingDataSource {

    static final String ADMIN_KEY = "ADMIN";
    static final String TENANT_KEY = "TENANT";

    @Override
    protected Object determineCurrentLookupKey() {
        TenantAccessContext context = TenantDataAccessContextHolder.snapshot();
        if (context == null) {
            if (TenantDataAccessBootstrap.isActive()) {
                return ADMIN_KEY;
            }
            throw new MissingTenantContextException(
                    "Tenant data-access context is not set on this thread. "
                            + "Call useAdmin(), useTenant(lspId), or TenantScopedExecution.callAsAdmin/callAsTenant "
                            + "before accessing tenant-scoped data."
            );
        }
        return context.mode() == TenantDataAccessMode.TENANT ? TENANT_KEY : ADMIN_KEY;
    }
}
