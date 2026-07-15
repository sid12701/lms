package com.bhawana.lms.tenant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class TenantDatasourceSecurityValidatorTest {

    @Test
    void rejectsLegacyDefaultPasswordOutsideLocalProfile() {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        properties.setPassword(TenantDatasourceSecurityValidator.LEGACY_DEFAULT_PASSWORD);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        TenantDatasourceSecurityValidator validator = new TenantDatasourceSecurityValidator(properties, environment);

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

        TenantDatasourceSecurityValidator validator = new TenantDatasourceSecurityValidator(properties, environment);

        validator.run(new DefaultApplicationArguments(new String[0]));
    }

    @Test
    void allowsLegacyDefaultPasswordWhenLocalIsDefaultProfile() {
        TenantAwareDataSourceProperties properties = new TenantAwareDataSourceProperties();
        properties.setPassword(TenantDatasourceSecurityValidator.LEGACY_DEFAULT_PASSWORD);
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");

        TenantDatasourceSecurityValidator validator = new TenantDatasourceSecurityValidator(properties, environment);

        validator.run(new DefaultApplicationArguments(new String[0]));
    }
}
