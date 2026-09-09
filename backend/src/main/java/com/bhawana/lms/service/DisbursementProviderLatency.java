package com.bhawana.lms.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * H27 — provider-call latency timers for the two bank network calls (payment request and status
 * check). No tags: latency is aggregated across LSPs so no tenant, borrower, account, or reference
 * identifier ever becomes a metric label. Wired at both network call sites, outside every
 * transaction; {@code Timer.record} captures timeouts as well as verdicts.
 */
@Component
public class DisbursementProviderLatency {

    private final Timer initiateTimer;
    private final Timer statusCheckTimer;

    public DisbursementProviderLatency(MeterRegistry meterRegistry) {
        this.initiateTimer = Timer.builder("lms.disbursement.provider.initiate.latency")
                .description("Latency of the bank payment-request network call")
                .register(meterRegistry);
        this.statusCheckTimer = Timer.builder("lms.disbursement.provider.status_check.latency")
                .description("Latency of the bank status-check network call")
                .register(meterRegistry);
    }

    /** Times the payment-request call, recording even when the call throws. */
    public <T> T timeInitiate(Supplier<T> call) {
        return initiateTimer.record(call);
    }

    /** Times the status-check call, recording even when the call throws. */
    public <T> T timeStatusCheck(Supplier<T> call) {
        return statusCheckTimer.record(call);
    }
}
