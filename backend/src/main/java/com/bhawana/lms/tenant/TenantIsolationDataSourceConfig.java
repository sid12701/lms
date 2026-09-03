package com.bhawana.lms.tenant;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(TenantAwareDataSourceProperties.class)
public class TenantIsolationDataSourceConfig {

    @Bean(name = "adminDataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    DataSource adminDataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "tenantPhysicalDataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    DataSource tenantPhysicalDataSource(
            DataSourceProperties dataSourceProperties,
            TenantAwareDataSourceProperties tenantProperties,
            @Qualifier("adminDataSource") DataSource adminDataSource
    ) {
        String jdbcUrl = dataSourceProperties.getUrl();
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
            return adminDataSource;
        }

        TenantConnectionStrategy strategy = tenantProperties.getConnectionStrategy();
        if (strategy == null) {
            strategy = TenantConnectionStrategy.DIRECT_LOGIN;
        }

        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        if (strategy == TenantConnectionStrategy.ASSUME_ROLE) {
            // Pool authenticates with the admin login; TenantAwareDataSource assumes
            // the tenant role inside each transaction (see ASSUME_ROLE strategy).
            dataSource.setUsername(dataSourceProperties.getUsername());
            dataSource.setPassword(dataSourceProperties.getPassword());
        } else {
            dataSource.setUsername(tenantProperties.getUsername());
            dataSource.setPassword(tenantProperties.getPassword());
        }
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
            DataSourceProperties dataSourceProperties,
            TenantAwareDataSourceProperties tenantProperties,
            @Qualifier("adminDataSource") DataSource adminDataSource,
            @Qualifier("tenantPhysicalDataSource") DataSource tenantPhysicalDataSource
    ) {
        String jdbcUrl = dataSourceProperties.getUrl();
        TenantAwareDataSource tenantAwareDataSource = jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:")
                ? new TenantAwareDataSource(
                        tenantPhysicalDataSource,
                        tenantProperties.getUsername(),
                        tenantProperties.getConnectionStrategy()
                )
                : new TenantAwareDataSource(
                        tenantPhysicalDataSource,
                        tenantProperties.getUsername(),
                        TenantConnectionStrategy.DIRECT_LOGIN
                );

        TenantRoutingDataSource routingDataSource = new TenantRoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(adminDataSource);
        routingDataSource.setTargetDataSources(Map.of(
                TenantRoutingDataSource.ADMIN_KEY, adminDataSource,
                TenantRoutingDataSource.TENANT_KEY, tenantAwareDataSource
        ));
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    @Bean(name = "dbHealthIndicator")
    HealthIndicator dbHealthIndicator(@Qualifier("adminDataSource") DataSource adminDataSource) {
        return () -> {
            try (Connection connection = adminDataSource.getConnection()) {
                return connection.isValid(2)
                        ? Health.up().build()
                        : Health.down().withDetail("error", "Database connection is not valid.").build();
            } catch (Exception ex) {
                return Health.down(ex).build();
            }
        };
    }
}
