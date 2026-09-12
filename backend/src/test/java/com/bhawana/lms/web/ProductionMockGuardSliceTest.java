package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.service.DisbursementSimulationGuard;
import com.bhawana.lms.service.LoanDisbursementMockProperties;
import com.bhawana.lms.service.MockLoanDisbursementAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * (Production mock guard) — actual Spring contexts booted with simulator-only wiring.
 * Production-like, default (no explicit profile) and mixed profiles fail closed; only explicit
 * simulation profiles (test/local/dev) boot the simulator.
 */
class ProductionMockGuardSliceTest {

    @Configuration
    static class SimulatorOnlyConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LoanDisbursementMockProperties mockProperties() {
            return new LoanDisbursementMockProperties();
        }

        @Bean
        DisbursementSimulationGuard simulationGuard(Environment environment) {
            return new DisbursementSimulationGuard(environment);
        }

        @Bean
        MockLoanDisbursementAdapter mockLoanDisbursementAdapter(
                ObjectMapper objectMapper,
                LoanDisbursementMockProperties properties,
                DisbursementSimulationGuard simulationGuard
        ) {
            return new MockLoanDisbursementAdapter(objectMapper, properties, simulationGuard);
        }
    }

    @Test
    void productionProfileWithSimulatorOnlyWiringFailsClosed() {
        BeanCreationException failure = assertThrows(BeanCreationException.class,
                () -> bootWithActiveProfiles("prod"));
        assertSimulationRefusal(failure);
    }

    @Test
    void defaultContextWithNoExplicitSimulationProfileFailsClosed() {
        BeanCreationException failure = assertThrows(BeanCreationException.class,
                () -> bootWithActiveProfiles());
        assertSimulationRefusal(failure);
    }

    @Test
    void mixedProductionProfileWinsOverSimulationProfile() {
        BeanCreationException failure = assertThrows(BeanCreationException.class,
                () -> bootWithActiveProfiles("test", "prod"));
        assertSimulationRefusal(failure);
    }

    @Test
    void explicitSimulationProfilesBootSimulator() {
        for (String profile : new String[]{"test", "local", "dev"}) {
            try (AnnotationConfigApplicationContext context = bootWithActiveProfiles(profile)) {
                assertEquals("MOCK_ICICI",
                        context.getBean(MockLoanDisbursementAdapter.class).providerName());
                assertTrue(context.getBean(DisbursementSimulationGuard.class).isSimulationAllowed());
            }
        }
    }

    private static AnnotationConfigApplicationContext bootWithActiveProfiles(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setActiveProfiles(activeProfiles);
        }
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(SimulatorOnlyConfig.class);
        context.refresh();
        return context;
    }

    private static void assertSimulationRefusal(BeanCreationException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof BusinessRuleViolationException violation) {
                assertEquals(DisbursementSimulationGuard.SIMULATION_NOT_ALLOWED, violation.getErrorCode());
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError(
                "Production boot with simulator-only wiring must fail with "
                        + DisbursementSimulationGuard.SIMULATION_NOT_ALLOWED + ", got: " + failure);
    }
}
