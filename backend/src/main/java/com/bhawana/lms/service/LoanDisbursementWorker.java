package com.bhawana.lms.service;

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

    public LoanDisbursementWorker(
            LoanDisbursementWorkerService workerService,
            LoanDisbursementWorkerProperties properties
    ) {
        this.workerService = workerService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.disbursement.worker.fixed-delay-ms:30000}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        int processed = TenantScopedExecution.callAsAdmin(workerService::processPendingDisbursements);
        if (processed > 0) {
            log.debug("Loan disbursement worker processed {} application(s).", processed);
        }
    }

    @Scheduled(fixedDelayString = "${app.disbursement.worker.status-check-delay-ms:60000}")
    public void runStatusChecks() {
        if (!properties.isEnabled()) {
            return;
        }
        int resolved = workerService.processPendingStatusChecks();
        if (resolved > 0) {
            log.debug("Loan disbursement status-check worker resolved {} transaction(s).", resolved);
        }
    }
}
