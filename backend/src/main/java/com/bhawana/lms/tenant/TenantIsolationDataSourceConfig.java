package com.bhawana.lms.tenant;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(TenantAwareDataSourceProperties.class)
public class TenantIsolationDataSourceConfig {

    @Bean(name = "adminDataSource")
    DataSource adminDataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "tenantPhysicalDataSource")
    DataSource tenantPhysicalDataSource(
            DataSourceProperties dataSourceProperties,
            TenantAwareDataSourceProperties tenantProperties,
            @Qualifier("adminDataSource") DataSource adminDataSource
    ) {
        String jdbcUrl = dataSourceProperties.getUrl();
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
            return adminDataSource;
        }

        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setUsername(tenantProperties.getUsername());
        dataSource.setPassword(tenantProperties.getPassword());
        dataSource.setInitializationFailTimeout(-1);
        dataSource.setPoolName("tenant-datasource");
        // Keep every tenant connection in manual-commit mode so the SET LOCAL
        // applied by TenantAwareDataSource is scoped to a real transaction,
        // including non-@Transactional reads. Hikari rolls back the pending
        // transaction on return-to-pool, which evicts the tenant binding.
        dataSource.setAutoCommit(false);
        return dataSource;
    }

    @Bean(name = "dataSource")
    @Primary
    DataSource routingDataSource(
            @Qualifier("adminDataSource") DataSource adminDataSource,
            @Qualifier("tenantPhysicalDataSource") DataSource tenantPhysicalDataSource
    ) {
        TenantRoutingDataSource routingDataSource = new TenantRoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(adminDataSource);
        routingDataSource.setTargetDataSources(Map.of(
                TenantRoutingDataSource.ADMIN_KEY, adminDataSource,
                TenantRoutingDataSource.TENANT_KEY, new TenantAwareDataSource(tenantPhysicalDataSource)
        ));
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }
}
