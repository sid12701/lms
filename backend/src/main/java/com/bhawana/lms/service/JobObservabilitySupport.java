package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdFilter;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * One scheduled-job tick of job-health telemetry (H27): sets a per-run correlation id on
 * {@link CorrelationIdHolder} and MDC for the duration and always clears both — scheduler
 * threads are pooled and must never leak context into the next job on the same thread.
 * Records a run-duration timer and an outcome counter per job name, and publishes the epoch
 * of each job's last successful run so a stuck or dying worker is alertable, not silent.
 *
 * <p>Metric labels carry only the job name and outcome — never tenant, borrower, account, or
 * request identifiers.
 */
@Component
public class JobObservabilitySupport {

    static final String MDC_JOB_NAME = "jobName";

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, AtomicLong> lastSuccessEpoch = new ConcurrentHashMap<>();

    public JobObservabilitySupport(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void run(String jobName, Runnable work) {
        run(jobName, () -> {
            work.run();
            return null;
        });
    }

    public <T> T run(String jobName, Supplier<T> work) {
        String runId = "job-" + jobName + "-" + UUID.randomUUID().toString().substring(0, 8);
        CorrelationIdHolder.set(runId);
        MDC.put(CorrelationIdFilter.ATTRIBUTE_NAME, runId);
        MDC.put(MDC_JOB_NAME, jobName);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = work.get();
            outcomeCounter(jobName, "success").increment();
            lastSuccess(jobName).set(Instant.now().getEpochSecond());
            return result;
        } catch (RuntimeException | Error failed) {
            outcomeCounter(jobName, "failure").increment();
            throw failed;
        } finally {
            sample.stop(Timer.builder("lms.job.run.duration")
                    .description("Wall-clock duration of one scheduled job tick")
                    .tag("job", jobName)
                    .register(meterRegistry));
            MDC.remove(CorrelationIdFilter.ATTRIBUTE_NAME);
            MDC.remove(MDC_JOB_NAME);
            CorrelationIdHolder.clear();
        }
    }

    private Counter outcomeCounter(String jobName, String outcome) {
        return Counter.builder("lms.job.runs")
                .description("Scheduled job ticks by outcome")
                .tags("job", jobName, "outcome", outcome)
                .register(meterRegistry);
    }

    private AtomicLong lastSuccess(String jobName) {
        return lastSuccessEpoch.computeIfAbsent(jobName, name -> {
            AtomicLong reference = new AtomicLong(-1);
            Gauge.builder("lms.job.last_success_epoch_seconds", reference, AtomicLong::get)
                    .description("Epoch seconds of the job's last successful tick; -1 until the first success")
                    .tags("job", name)
                    .strongReference(true)
                    .register(meterRegistry);
            return reference;
        });
    }
}
