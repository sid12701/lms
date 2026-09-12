package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guard matrix. Simulation is permitted only by explicit simulation active profiles;
 * production-like, default (no active profile) and mixed contexts fail closed.
 */
class DisbursementSimulationGuardTest {

    @Test
    void explicitSimulationProfilesAreAllowed() {
        for (String profile : new String[]{"test", "local", "dev"}) {
            assertTrue(new DisbursementSimulationGuard(env(profile)).isSimulationAllowed(), profile);
        }
    }

    @Test
    void productionLikeProfilesAreDenied() {
        for (String profile : new String[]{"prod", "production", "staging", "uat"}) {
            assertFalse(new DisbursementSimulationGuard(env(profile)).isSimulationAllowed(), profile);
        }
    }

    @Test
    void defaultContextWithNoActiveProfileIsDenied() {
        assertFalse(new DisbursementSimulationGuard(new MockEnvironment()).isSimulationAllowed());
    }

    @Test
    void productionWinsOverMixedProfiles() {
        assertFalse(new DisbursementSimulationGuard(env("test", "prod")).isSimulationAllowed());
        assertFalse(new DisbursementSimulationGuard(env("local", "staging")).isSimulationAllowed());
    }

    @Test
    void profileMatchingIsCaseInsensitiveAndTrimmed() {
        assertTrue(new DisbursementSimulationGuard(env("TEST")).isSimulationAllowed());
        assertTrue(new DisbursementSimulationGuard(env(" local ")).isSimulationAllowed());
    }

    @Test
    void nullEnvironmentIsDenied() {
        assertFalse(new DisbursementSimulationGuard(null).isSimulationAllowed());
    }

    @Test
    void requireSimulationAllowedFailsClosedWithStableErrorCode() {
        BusinessRuleViolationException failure = assertThrows(BusinessRuleViolationException.class,
                () -> new DisbursementSimulationGuard(env("prod")).requireSimulationAllowed("mock-outcome"));
        assertEquals(DisbursementSimulationGuard.SIMULATION_NOT_ALLOWED, failure.getErrorCode());
    }

    private static MockEnvironment env(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }
}
