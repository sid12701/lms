package com.bhawana.lms.service;

import com.bhawana.lms.config.ScheduledJobThreadingConfig;
import com.bhawana.lms.tenant.TenantScopedExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LoanDisbursementWorker {

    private static final Logger log = LoggerFactory.getLogger(LoanDisbursementWorker.class);

    private final LoanDisbursementWorkerService workerService;
    private final LoanDisbursementWorkerProperties properties;
    private final JobObservabilitySupport jobObservability;

    public LoanDisbursementWorker(
            LoanDisbursementWorkerService workerService,
            LoanDisbursementWorkerProperties properties,
            JobObservabilitySupport jobObservability
    ) {
        this.workerService = workerService;
        this.properties = properties;
        this.jobObservability = jobObservability;
    }

    @Scheduled(fixedDelayString = "${app.disbursement.worker.fixed-delay-ms:30000}", scheduler = ScheduledJobThreadingConfig.FINANCIAL_TASK_SCHEDULER)
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        int processed = jobObservability.run("disbursement-pending", () ->
                TenantScopedExecution.callAsAdmin(workerService::processPendingDisbursements));
        if (processed > 0) {
            log.debug("Loan disbursement worker processed {} application(s).", processed);
        }
    }

    @Scheduled(fixedDelayString = "${app.disbursement.worker.status-check-delay-ms:60000}", scheduler = ScheduledJobThreadingConfig.FINANCIAL_TASK_SCHEDULER)
    public void runStatusChecks() {
        if (!properties.isEnabled()) {
            return;
        }
        int resolved = jobObservability.run("disbursement-status-checks", workerService::processPendingStatusChecks);
        if (resolved > 0) {
            log.debug("Loan disbursement status-check worker resolved {} transaction(s).", resolved);
        }
    }

    /**
     * Bounded reconciliation sweep on its own schedule. Gated by the same existing
     * worker {@code enabled} flag; normal disbursement and status-check ticks are untouched.
     */
    @Scheduled(fixedDelayString = "${app.disbursement.worker.reconciliation-delay-ms:60000}", scheduler = ScheduledJobThreadingConfig.FINANCIAL_TASK_SCHEDULER)
    public void runReconciliation() {
        if (!properties.isEnabled()) {
            return;
        }
        int resolved = jobObservability.run("disbursement-reconciliation", workerService::processReconciliationQueue);
        if (resolved > 0) {
            log.debug("Loan disbursement reconciliation worker resolved {} entries.", resolved);
        }
    }
}
