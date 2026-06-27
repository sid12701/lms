package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationInvalidationService {

    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final WebhookOutboxService webhookOutboxService;

    public LoanApplicationInvalidationService(
            LoanAccountRepository loanAccountRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            WebhookOutboxService webhookOutboxService
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.webhookOutboxService = webhookOutboxService;
    }

    @Transactional
    public LoanApplication invalidateApplicationForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        LoanApplication application = loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_Id(applicationId).orElse(null);
        return invalidateApplication(application, loanAccount, actorUsername, invalidReason, invalidReasonText);
    }

    @Transactional
    public LoanApplication invalidateApplication(
            LoanApplication application,
            LoanAccount loanAccount,
            String actorUsername,
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        if (invalidReason == null) {
            throw new IllegalArgumentException("Invalid loan reason is required.");
        }

        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus == LoanApplicationStatus.INVALID) {
            throw new ApiConflictException("LOAN_ALREADY_INVALID", "Loan application is already marked invalid.");
        }
        if (currentStatus.hasEnteredServicing()) {
            throw new BusinessRuleViolationException(
                    "INVALIDATION_NOT_ALLOWED",
                    "Loan applications that have entered servicing cannot be marked invalid.",
                    Map.of("status", currentStatus.name())
            );
        }

        if (loanAccount != null) {
            if (loanAccount.getStatus() == LoanAccountStatus.DISBURSED
                    || loanAccount.getStatus() == LoanAccountStatus.CLOSED
                    || loanAccount.getStatus() == LoanAccountStatus.FORECLOSED) {
                throw new BusinessRuleViolationException(
                        "INVALIDATION_NOT_ALLOWED",
                        "Loan applications that have entered servicing cannot be marked invalid.",
                        Map.of("loanAccountStatus", loanAccount.getStatus().name())
                );
            }
            if (loanAccount.getStatus() == LoanAccountStatus.INVALID) {
                throw new ApiConflictException("LOAN_ALREADY_INVALID", "Loan application is already marked invalid.");
            }
        }

        String normalizedActor = Strings.normalizeActor(actorUsername);
        String normalizedInvalidReasonText = normalizeInvalidReasonText(invalidReason, invalidReasonText);
        Instant invalidatedAt = Instant.now();
        String invalidationNote = buildInvalidationNote(invalidReason, normalizedInvalidReasonText);

        application.markInvalid(
                invalidReason,
                normalizedInvalidReasonText,
                normalizedActor,
                invalidatedAt
        );

        if (loanAccount != null) {
            loanAccount.markInvalid();
            loanAccountRepository.save(loanAccount);
        }

        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                LoanApplicationStatus.INVALID,
                normalizedActor,
                invalidationNote,
                null,
                CorrelationIdHolder.get()
        ));
        loanApplicationStatusWriter.recordAuditEvent(
                savedApplication,
                LoanApplicationAuditAction.INVALIDATED,
                currentStatus,
                LoanApplicationStatus.INVALID,
                normalizedActor,
                invalidationNote,
                null
        );
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_STATUS_CHANGED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                LoanWebhookPayloads.loanStatusChanged(
                        savedApplication,
                        currentStatus,
                        LoanApplicationStatus.INVALID,
                        null
                )
        );
        return savedApplication;
    }

    private static String normalizeInvalidReasonText(
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        String normalized = Strings.normalizeOptional(invalidReasonText);
        if (invalidReason.requiresDetail() && normalized == null) {
            throw new IllegalArgumentException("Other invalid loan reason text is required when reason is OTHERS.");
        }
        if (!invalidReason.requiresDetail() && normalized != null) {
            throw new IllegalArgumentException("Other invalid loan reason text is only allowed when reason is OTHERS.");
        }
        return normalized;
    }

    private static String buildInvalidationNote(
            LoanInvalidationReason invalidReason,
            String invalidReasonText
    ) {
        if (invalidReason.requiresDetail()) {
            return "Loan marked invalid. Reason: " + invalidReason.getLabel() + " - " + invalidReasonText;
        }
        return "Loan marked invalid. Reason: " + invalidReason.getLabel();
    }
}
