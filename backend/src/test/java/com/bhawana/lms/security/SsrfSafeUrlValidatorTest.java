package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SsrfSafeUrlValidatorTest {

    @Test
    void deliveryValidationRejectsUnresolvedHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SsrfSafeUrlValidator.validate("https://nonexistent.invalid/hook")
        );
        assert ex.getMessage().contains("could not be resolved");
    }

    @Test
    void rejectsLoopbackHost() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SsrfSafeUrlValidator.validate("https://127.0.0.1/hook")
        );
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SsrfSafeUrlValidator.validateRegistrationTarget("ftp://partner.example.com/hook")
        );
    }

    @Test
    void registrationAllowsCurrentlyUnresolvableHost() {
        // A partner endpoint may not resolve when first configured; egress is still gated at delivery.
        assertDoesNotThrow(
                () -> SsrfSafeUrlValidator.validateRegistrationTarget("https://partner.example.com/hook")
        );
    }

    @Test
    void registrationStillBlocksLoopback() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SsrfSafeUrlValidator.validateRegistrationTarget("https://127.0.0.1/hook")
        );
    }
}
