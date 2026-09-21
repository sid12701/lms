package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * M06: alert-rule configuration is typed {@code app.alert-rules.*} only. Unsupported
 * keys and out-of-range values must fail at bind time, not silently evaluate with
 * defaults — the API displays these values as the evaluated truth.
 */
class AlertRulePropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsDefaultsCleanly() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AlertRuleProperties properties = context.getBean(AlertRuleProperties.class);
            assertThat(properties.getStaleIntakeHours()).isEqualTo(24);
        });
    }

    @Test
    void rejectsNonPositiveThresholds() {
        contextRunner
                .withPropertyValues("app.alert-rules.stale-intake-hours=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.alert-rules.auth-brute-force-threshold=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsOutOfRangeRejectRate() {
        contextRunner
                .withPropertyValues("app.alert-rules.lsp-reject-rate-pct=140")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnknownKeysInsteadOfSilentlyIgnoringThem() {
        // A misspelled operator override used to bind as a no-op; with the retired
        // config_json gone there is nowhere for a stray key to hide, so it must fail.
        contextRunner
                .withPropertyValues("app.alert-rules.stale-intake-hour=2")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AlertRuleProperties.class)
    static class TestConfiguration {
    }
}
