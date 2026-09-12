package com.bhawana.lms.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Exemption unit matrix for {@link DeploymentProfiles#isDevExempt}.
 */
class DeploymentProfilesTest {

    @Test
    void unsetProfilesAreNotExempt() {
        assertFalse(DeploymentProfiles.isDevExempt(new MockEnvironment()));
    }

    @Test
    void defaultProfilesNeverExempt() {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");
        assertFalse(DeploymentProfiles.isDevExempt(environment));
    }

    @Test
    void nullEnvironmentIsNotExempt() {
        assertFalse(DeploymentProfiles.isDevExempt(null));
    }

    @Test
    void explicitLocalAndTestProfilesAreExempt() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertTrue(DeploymentProfiles.isDevExempt(local));

        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertTrue(DeploymentProfiles.isDevExempt(test));

        MockEnvironment both = new MockEnvironment();
        both.setActiveProfiles("local", "test");
        assertTrue(DeploymentProfiles.isDevExempt(both));
    }

    @Test
    void misspelledProdAndMixedProfilesAreNotExempt() {
        MockEnvironment misspelled = new MockEnvironment();
        misspelled.setActiveProfiles("loca");
        assertFalse(DeploymentProfiles.isDevExempt(misspelled));

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertFalse(DeploymentProfiles.isDevExempt(prod));

        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("local", "prod");
        assertFalse(DeploymentProfiles.isDevExempt(mixed));

        // Simulation-only "dev" still enforces deployment safety checks.
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertFalse(DeploymentProfiles.isDevExempt(dev));
    }

    @Test
    void profileMatchingIsExactCaseAndWhitespaceVariantsAreNotExempt() {
        // Spring resolves application-<profile>.yml case-sensitively, so these variants never
        // load the development document and must not earn exemptions either.
        for (String variant : new String[]{"LOCAL", "Local", "TEST", " local", "local ", " Local "}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(variant);
            assertFalse(
                    DeploymentProfiles.isDevExempt(environment),
                    "Profile variant must not be exempt: '" + variant + "'");
        }
    }
}
