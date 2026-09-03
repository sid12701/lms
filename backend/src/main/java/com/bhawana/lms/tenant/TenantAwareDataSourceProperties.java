package com.bhawana.lms.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.datasource.tenant")
public class TenantAwareDataSourceProperties {

    private String username;
    private String password;
    private TenantConnectionStrategy connectionStrategy = TenantConnectionStrategy.DIRECT_LOGIN;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public TenantConnectionStrategy getConnectionStrategy() {
        return connectionStrategy;
    }

    public void setConnectionStrategy(TenantConnectionStrategy connectionStrategy) {
        this.connectionStrategy = connectionStrategy;
    }
}
