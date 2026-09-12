package com.bhawana.lms.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The raw-socket-peer prerequisite is enforced on real bound configuration, not by
 * asserting YAML text. Each case binds actual {@link ServerProperties} from property
 * values and runs the production validator bean — NATIVE/FRAMEWORK/RemoteIpValve
 * overrides fail context startup, explicit NONE (and the off-cloud unset default) start
 * clean.
 */
class EdgeForwardingPrerequisiteValidatorTest {

    @Configuration
    @EnableConfigurationProperties(ServerProperties.class)
    @Import(EdgeForwardingPrerequisiteValidator.class)
    static class ValidatorConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidatorConfig.class));

    @Test
    void nativeOverrideFailsStartup() {
        runner.withPropertyValues("server.forward-headers-strategy=NATIVE")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("server.forward-headers-strategy must be NONE");
                });
    }

    @Test
    void frameworkOverrideFailsStartup() {
        runner.withPropertyValues("server.forward-headers-strategy=FRAMEWORK")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("server.forward-headers-strategy must be NONE");
                });
    }

    @Test
    void explicitRemoteIpValveHeaderFailsStartupEvenUnderNone() {
        runner.withPropertyValues(
                        "server.forward-headers-strategy=NONE",
                        "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("server.tomcat.remoteip");
                });
    }

    @Test
    void explicitProtocolHeaderFailsStartupEvenUnderNone() {
        runner.withPropertyValues(
                        "server.forward-headers-strategy=NONE",
                        "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("server.tomcat.remoteip");
                });
    }

    @Test
    void explicitNoneStartsClean() {
        runner.withPropertyValues("server.forward-headers-strategy=NONE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EdgeForwardingPrerequisiteValidator.class);
                });
    }

    @Test
    void unsetStrategyStartsCleanOffCloud() {
        // Documents Boot's own default resolution: no cloud platform detected in this
        // environment, so unset means NONE-equivalent and the validator passes. (On a
        // detected cloud platform the same unset input fails, mirroring Boot's NATIVE
        // default there — production always sets the property explicitly regardless.)
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EdgeForwardingPrerequisiteValidator.class);
        });
    }
}
