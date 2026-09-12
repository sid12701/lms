package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issue #62 — automated disbursement for approved applications (replaces LSP-initiated disbursement).
 */
@Service
public class LoanDisbursementWorkerService {

    public static final String WORKER_ACTOR = "SYSTEM_DISBURSEMENT_WORKER";

    private static final Logger log = LoggerFactory.getLogger(LoanDisbursementWorkerService.class);

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanDisbursementCommandService loanDisbursementCommandService;
    private final LoanDisbursementWorkerProcessor workerProcessor;
    private final DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    private final DisbursementReconciliationService reconciliationService;
    private final LoanDisbursementWorkerProperties properties;
    private final Counter itemFailureCounter;
    private final Counter scanFailureCounter;

    public LoanDisbursementWorkerService(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanDisbursementCommandService loanDisbursementCommandService,
            LoanDisbursementWorkerProcessor workerProcessor,
            DisbursementIntentWorkflowService disbursementIntentWorkflowService,
            DisbursementReconciliationService reconciliationService,
            LoanDisbursementWorkerProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.workerProcessor = workerProcessor;
        this.disbursementIntentWorkflowService = disbursementIntentWorkflowService;
        this.reconciliationService = reconciliationService;
        this.properties = properties;
        // Low-cardinality failure counters only — never application, account, or
        // request identifiers in tags. Gauges for intent backlogs stay with the
        // DisbursementIntentMetrics; these counters record worker-side failures.
        this.itemFailureCounter = Counter.builder("lms.disbursement.worker.item.failures")
                .description("Disbursement worker per-item failures; the tick continues with the next item")
                .register(meterRegistry);
        this.scanFailureCounter = Counter.builder("lms.disbursement.worker.scan.failures")
                .description("Disbursement worker scan failures; the phase contributes zero and the tick continues")
                .register(meterRegistry);
    }

    /**
     * Processes a single application: {@link LoanDisbursementWorkerProcessor} runs guarded
     * validation plus Tx-A intent creation in ONE transaction (via the Spring proxy), then
     * the committed intent is executed here, outside any transaction (the provider call
     * must never share the initiating transaction).
     *
     * <p>The catch below runs only AFTER the processor transaction has ended — never
     * inside a rollback-only transaction. A failure returns false (never processed) and
     * increments the failure counter, so one parked or conflicted loan can never abort
     * the rest of the tick.
     */
    public boolean processApplication(UUID applicationId) {
        final boolean actioned;
        try {
            actioned = workerProcessor.processApplication(applicationId);
        } catch (RuntimeException exception) {
            itemFailureCounter.increment();
            log.warn(
                    "Automated disbursement failed for application {}: {}",
                    applicationId,
                    exception.getMessage()
            );
            return false;
        }
        if (actioned) {
            executeCommittedIntent(applicationId);
        }
        return actioned;
    }

    private void executeCommittedIntent(UUID applicationId) {
        TenantScopedExecution.runAsAdmin(() ->
                disbursementIntentWorkflowService.executeForApplication(applicationId).ifPresent(executedApplicationId -> {
                    if (properties.isAutoResolveMockOutcome()) {
                        loanDisbursementCommandService.autoResolveAfterInitiate(
                                executedApplicationId,
                                WORKER_ACTOR,
                                null,
                                CorrelationIdHolder.get()
                        );
                    }
                }));
    }

    /**
     * Each phase isolates its own scan and per-item failures and contributes zero
     * on failure, so application scanning and bounded intent recovery (claimable
     * execution, stranded repair) always run independently in the same tick.
     */
    public int processPendingDisbursements() {
        return TenantScopedExecution.callAsAdmin(() -> {
            int processed = 0;
            processed += processStatus(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
            processed += processStatus(LoanApplicationStatus.DISBURSEMENT_RETRY);
            processed += processClaimableIntents();
            processed += processStrandedTerminalResults();
            return processed;
        });
    }

    public int processClaimableIntents() {
        return TenantScopedExecution.callAsAdmin(() -> {
            // Scan and per-item isolation live in executeClaimableIntents (claim-fenced,
            // CREATED-only); only the mock auto-resolve loop needs a per-item guard here.
            List<UUID> applicationIds = disbursementIntentWorkflowService.executeClaimableIntents();
            int resolved = 0;
            for (UUID applicationId : applicationIds) {
                if (!properties.isAutoResolveMockOutcome()) {
                    resolved++;
                    continue;
                }
                try {
                    loanDisbursementCommandService.autoResolveAfterInitiate(
                            applicationId,
                            WORKER_ACTOR,
                            null,
                            CorrelationIdHolder.get()
                    );
                    resolved++;
                } catch (RuntimeException exception) {
                    itemFailureCounter.increment();
                    log.warn(
                            "Claimable intent auto-resolve failed for application {}: {}",
                            applicationId,
                            exception.getMessage()
                    );
                }
            }
            return resolved;
        });
    }

    public int processStrandedTerminalResults() {
        return TenantScopedExecution.callAsAdmin(
                () -> disbursementIntentWorkflowService.repairStrandedTerminalDisbursements(20));
    }

    /**
     * Bounded reconciliation sweep phase, invoked from its own schedule (never from
     * the normal disbursement/status-check ticks, whose behavior is unchanged). Admin scope is
     * entered before any transaction; per-item isolation lives in
     * {@link DisbursementReconciliationService#pollDueQueue}. A sweep-level failure contributes
     * zero and never aborts the other worker phases.
     */
    public int processReconciliationQueue() {
        try {
            return TenantScopedExecution.callAsAdmin(() ->
                    reconciliationService.pollDueQueue(
                            WORKER_ACTOR, null, CorrelationIdHolder.get()));
        } catch (RuntimeException exception) {
            scanFailureCounter.increment();
            log.warn("Disbursement reconciliation sweep failed and was skipped: {}",
                    exception.getMessage());
            return 0;
        }
    }

    private int processStatus(LoanApplicationStatus status) {
        final List<LoanApplication> applications;
        try {
            applications = loanApplicationRepository.findByStatus(status);
        } catch (RuntimeException exception) {
            scanFailureCounter.increment();
            log.warn("Disbursement worker scan failed for status {} and was skipped: {}", status, exception.getMessage());
            return 0;
        }
        int processed = 0;
        for (LoanApplication application : applications) {
            try {
                if (processApplication(application.getId())) {
                    processed++;
                }
            } catch (RuntimeException exception) {
                // Committed-intent execution runs outside any transaction; a failure there
                // is isolated per item the same way.
                itemFailureCounter.increment();
                log.warn(
                        "Disbursement worker item failed for application {}: {}",
                        application.getId(),
                        exception.getMessage()
                );
            }
        }
        return processed;
    }

    /**
     * Polls the mock provider for every disbursement still awaiting a terminal status (NEFT and IMPS
     * timeout codes left PENDING). Each transaction is resolved in its own transaction so a single
     * failure cannot roll back the batch.
     */
    public int processPendingStatusChecks() {
        return TenantScopedExecution.callAsAdmin(() -> {
            List<UUID> applicationIds = loanAccountRepository
                    .findByStatus(LoanAccountStatus.DISBURSEMENT_REQUESTED)
                    .stream()
                    .map(account -> account.getLoanApplication().getId())
                    .toList();
            int resolved = 0;
            for (UUID applicationId : applicationIds) {
                try {
                    if (loanDisbursementCommandService.pollPendingDisbursement(
                            applicationId, WORKER_ACTOR, null, CorrelationIdHolder.get())) {
                        resolved++;
                    }
                } catch (RuntimeException exception) {
                    log.warn(
                            "Disbursement status check failed for application {}: {}",
                            applicationId,
                            exception.getMessage()
                    );
                }
            }
            return resolved;
        });
    }
}
