package com.bhawana.lms.tenant;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

final class TenantRoutingDataSource extends AbstractRoutingDataSource {

    static final String ADMIN_KEY = "ADMIN";
    static final String TENANT_KEY = "TENANT";

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantDataAccessContextHolder.getMode() == TenantDataAccessMode.TENANT
                ? TENANT_KEY
                : ADMIN_KEY;
    }
}
