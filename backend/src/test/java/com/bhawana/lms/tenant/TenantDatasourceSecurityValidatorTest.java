package com.bhawana.lms.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

class TenantDatasourceSecurityValidatorTest {

    @Test
    void rejectsLegacyDefaultPasswordOutsideLocalProfile() {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        properties.setPassword(TenantDatasourceSecurityValidator.LEGACY_DEFAULT_PASSWORD);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        TenantDatasourceSecurityValidator validator = new TenantDatasourceSecurityValidator(
                properties,
                new DataSourceProperties(),
                stubRoutingDataSource(),
                environment
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments(new String[0]))
        );
        assertTrue(exception.getMessage().contains("legacy default"));
    }

    @Test
    void allowsLegacyDefaultPasswordOnLocalProfile() {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        properties.setPassword(TenantDatasourceSecurityValidator.LEGACY_DEFAULT_PASSWORD);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        TenantDatasourceSecurityValidator validator = new TenantDatasourceSecurityValidator(
                properties,
                new DataSourceProperties(),
                stubRoutingDataSource(),
                environment
        );

        validator.run(new DefaultApplicationArguments(new String[0]));
    }

    @Test
    void allowsLegacyDefaultPasswordWhenLocalIsDefaultProfile() {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        properties.setPassword(TenantDatasourceSecurityValidator.LEGACY_DEFAULT_PASSWORD);
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");

        TenantDatasourceSecurityValidator validator = new TenantDatasourceSecurityValidator(
                properties,
                new DataSourceProperties(),
                stubRoutingDataSource(),
                environment
        );

        validator.run(new DefaultApplicationArguments(new String[0]));
    }

    @Test
    void defaultsConnectionStrategyToDirectLogin() {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        assertEquals(TenantConnectionStrategy.DIRECT_LOGIN, properties.getConnectionStrategy());
    }

    private static DataSource stubRoutingDataSource() {
        return new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:h2:mem:validator;DB_CLOSE_DELAY=-1", true);
    }
}
