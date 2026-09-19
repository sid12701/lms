package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * H27 job-health contract: every scheduled tick carries a per-run correlation id on MDC and
 * the correlation holder, records its duration and outcome, publishes a last-success gauge,
 * and — because scheduler threads are pooled — leaves no context behind on the thread.
 */
class JobObservabilitySupportTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final JobObservabilitySupport jobObservability = new JobObservabilitySupport(meterRegistry);

    @AfterEach
    void clearContext() {
        MDC.clear();
        CorrelationIdHolder.clear();
    }

    @Test
    void successfulRunPublishesDurationOutcomeAndLastSuccess() {
        AtomicReference<String> correlationDuringRun = new AtomicReference<>();
        AtomicReference<String> mdcJobDuringRun = new AtomicReference<>();

        String result = jobObservability.run("test-job", () -> {
            correlationDuringRun.set(CorrelationIdHolder.get());
            mdcJobDuringRun.set(MDC.get(JobObservabilitySupport.MDC_JOB_NAME));
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(correlationDuringRun.get()).startsWith("job-test-job-");
        assertThat(MDC.get("correlationId")).as("MDC correlation is cleared after the run").isNull();
        assertThat(mdcJobDuringRun.get()).isEqualTo("test-job");

        assertThat(meterRegistry.get("lms.job.runs")
                .tags("job", "test-job", "outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("lms.job.runs")
                .tags("job", "test-job", "outcome", "failure").counter())
                .isNull();
        assertThat(meterRegistry.get("lms.job.run.duration")
                .tag("job", "test-job").timer().count())
                .isEqualTo(1L);
        assertThat(meterRegistry.get("lms.job.last_success_epoch_seconds")
                .tag("job", "test-job").gauge().value())
                .isPositive();
    }

    @Test
    void failedRunRecordsFailurePropagatesAndClearsContext() {
        AtomicReference<String> correlationDuringRun = new AtomicReference<>();

        assertThatThrownBy(() -> jobObservability.run("broken-job", () -> {
            correlationDuringRun.set(CorrelationIdHolder.get());
            throw new IllegalStateException("forced worker failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("forced worker failure");

        assertThat(correlationDuringRun.get()).startsWith("job-broken-job-");
        assertThat(meterRegistry.get("lms.job.runs")
                .tags("job", "broken-job", "outcome", "failure").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("lms.job.runs")
                .tags("job", "broken-job", "outcome", "success").counter())
                .isNull();
        assertThat(meterRegistry.get("lms.job.run.duration")
                .tag("job", "broken-job").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void contextDoesNotLeakBetweenRunsOnTheSameThread() {
        AtomicReference<String> firstRunId = new AtomicReference<>();
        jobObservability.run("job-a", () -> firstRunId.set(CorrelationIdHolder.get()));

        assertThat(CorrelationIdHolder.get()).isNull();
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get(JobObservabilitySupport.MDC_JOB_NAME)).isNull();

        AtomicReference<String> secondRunId = new AtomicReference<>();
        jobObservability.run("job-b", () -> secondRunId.set(CorrelationIdHolder.get()));

        assertThat(secondRunId.get()).startsWith("job-job-b-");
        assertThat(secondRunId.get()).isNotEqualTo(firstRunId.get());
        assertThat(CorrelationIdHolder.get()).isNull();
    }
}
