package com.bhawana.lms.seed.synthetic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhawana.lms.service.ApiClientManagementService;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * L05 runtime gates: even inside an approved profile the seeder must fail closed unless every
 * independent gate is open — no production profile, {@code enabled=true}, and the connected
 * database explicitly present in {@code allowed-database-names}. These tests never touch a
 * database; {@link SyntheticPortfolioSeedService#currentDatabaseName()} is stubbed instead.
 */
class SyntheticPortfolioSeedGuardTest {

    private static SyntheticPortfolioSeedService service(
            SyntheticPortfolioSeedProperties properties,
            String databaseName,
            String... activeProfiles
    ) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);
        DataSource adminDataSource = mock(DataSource.class);
        ApiClientManagementService apiClients = mock(ApiClientManagementService.class);
        return new SyntheticPortfolioSeedService(
                adminDataSource,
                properties,
                apiClients,
                environment
        ) {
            @Override
            String currentDatabaseName() {
                return databaseName;
            }
        };
    }

    private static SyntheticPortfolioSeedProperties properties(boolean enabled, List<String> allowedDatabases) {
        SyntheticPortfolioSeedProperties properties = new SyntheticPortfolioSeedProperties();
        properties.setEnabled(enabled);
        properties.setAllowedDatabaseNames(allowedDatabases);
        return properties;
    }

    @Test
    void disabledFlagBlocksSeeding() {
        SyntheticPortfolioSeedService service =
                service(properties(false, List.of("lms")), "lms", "local");

        assertThatThrownBy(service::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enabled=true");
    }

    @Test
    void productionProfileBlocksSeedingEvenWhenEnabled() {
        SyntheticPortfolioSeedService service =
                service(properties(true, List.of("lms")), "lms", "prod");

        assertThatThrownBy(service::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");
    }

    @Test
    void emptyDatabaseAllowlistFailsClosed() {
        SyntheticPortfolioSeedService service =
                service(properties(true, List.of()), "lms", "staging");

        assertThatThrownBy(service::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-database-names");
    }

    @Test
    void nonAllowlistedDatabaseIsRefused() {
        SyntheticPortfolioSeedService service =
                service(properties(true, List.of("lms-local", "lms-staging")), "lms_prod", "staging");

        assertThatThrownBy(service::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-database-names");
    }
}
