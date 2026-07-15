package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
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
    private final DisbursementIntentWorkflowProperties intentWorkflowProperties;
    private final LoanDisbursementWorkerProperties properties;

    public LoanDisbursementWorkerService(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanDisbursementCommandService loanDisbursementCommandService,
            LoanDisbursementWorkerProcessor workerProcessor,
            DisbursementIntentWorkflowService disbursementIntentWorkflowService,
            DisbursementIntentWorkflowProperties intentWorkflowProperties,
            LoanDisbursementWorkerProperties properties
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.workerProcessor = workerProcessor;
        this.disbursementIntentWorkflowService = disbursementIntentWorkflowService;
        this.intentWorkflowProperties = intentWorkflowProperties;
        this.properties = properties;
    }

    /**
     * Processes a single application: {@link LoanDisbursementWorkerProcessor} runs the validation
     * and initiation in its own transaction, then — with the intent workflow on — the committed
     * intent is executed here, outside any transaction (S3: the provider call must never share the
     * initiating transaction).
     */
    public boolean processApplication(UUID applicationId) {
        boolean actioned = workerProcessor.processApplication(applicationId);
        if (actioned) {
            executeCommittedIntent(applicationId);
        }
        return actioned;
    }

    private void executeCommittedIntent(UUID applicationId) {
        if (!intentWorkflowProperties.isEnabled()) {
            return;
        }
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

    public int processPendingDisbursements() {
        return TenantScopedExecution.callAsAdmin(() -> {
            int processed = 0;
            processed += processStatus(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
            processed += processStatus(LoanApplicationStatus.DISBURSEMENT_RETRY);
            processed += processClaimableIntents();
            return processed;
        });
    }

    public int processClaimableIntents() {
        return TenantScopedExecution.callAsAdmin(() -> {
            List<UUID> applicationIds = disbursementIntentWorkflowService.executeClaimableIntents();
            if (properties.isAutoResolveMockOutcome()) {
                for (UUID applicationId : applicationIds) {
                    loanDisbursementCommandService.autoResolveAfterInitiate(
                            applicationId,
                            WORKER_ACTOR,
                            null,
                            CorrelationIdHolder.get()
                    );
                }
            }
            return applicationIds.size();
        });
    }

    private int processStatus(LoanApplicationStatus status) {
        List<LoanApplication> applications = loanApplicationRepository.findByStatus(status);
        int processed = 0;
        for (LoanApplication application : applications) {
            if (processApplication(application.getId())) {
                processed++;
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
