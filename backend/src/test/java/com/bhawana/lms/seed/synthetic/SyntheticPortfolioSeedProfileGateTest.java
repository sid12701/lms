package com.bhawana.lms.seed.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/**
 * L05 profile gating: the synthetic seed beans must only be registered under the explicitly
 * approved {@code local}, {@code staging}, or {@code test-data} profiles. The scan registers
 * bean definitions without instantiating them, so this needs no datasource and cannot run a
 * seed — it proves that under {@code prod}, {@code production}, the generic {@code test}
 * profile, an unknown profile, or no profile at all, nothing in the application (controller,
 * runner, or any other bean) can resolve the destructive seeder.
 */
class SyntheticPortfolioSeedProfileGateTest {

    private static final String SEED_PACKAGE = "com.bhawana.lms.seed.synthetic";
    private static final Set<String> SEED_BEANS = Set.of(
            "syntheticPortfolioSeedService",
            "syntheticPortfolioSeedRunner",
            "syntheticPortfolioSeedConfiguration"
    );

    private static Set<String> scannedSeedBeans(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profiles);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            new ClassPathBeanDefinitionScanner(context).scan(SEED_PACKAGE);
            return Arrays.stream(context.getBeanDefinitionNames())
                    .filter(SEED_BEANS::contains)
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void approvedProfilesRegisterTheSeederBeans() {
        for (String profile : new String[] {"local", "staging", "test-data"}) {
            assertThat(scannedSeedBeans(profile))
                    .as("profile %s must expose the synthetic seed beans", profile)
                    .isEqualTo(SEED_BEANS);
        }
    }

    @Test
    void productionProfilesDoNotRegisterTheSeederBeans() {
        for (String profile : new String[] {"prod", "production"}) {
            assertThat(scannedSeedBeans(profile))
                    .as("profile %s must fail closed with no synthetic seed beans", profile)
                    .isEmpty();
        }
    }

    @Test
    void genericTestProfileDoesNotRegisterTheSeederBeans() {
        // Tests that need the seeder must opt in via the dedicated test-data profile.
        assertThat(scannedSeedBeans("test")).isEmpty();
    }

    @Test
    void unknownOrAbsentProfilesDoNotRegisterTheSeederBeans() {
        assertThat(scannedSeedBeans("qa-demo")).isEmpty();
        assertThat(scannedSeedBeans()).isEmpty();
    }
}
