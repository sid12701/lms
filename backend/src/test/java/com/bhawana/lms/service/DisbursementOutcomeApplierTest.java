package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementOutcomeAuditOutcome;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the disposition dispatch table extracted into {@link DisbursementOutcomeApplier}: each
 * provider verdict maps to the right loan-account state, application transition, partner webhook,
 * ops alert and outcome-audit category.
 */
@ExtendWith(MockitoExtension.class)
class DisbursementOutcomeApplierTest {

    @Mock private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Mock private LoanAccountRepository loanAccountRepository;
    @Mock private LoanApplicationStatusWriter loanApplicationStatusWriter;
    @Mock private WebhookOutboxService webhookOutboxService;
    @Mock private OpsAlertEmitters opsAlertEmitters;
    @Mock private DisbursementOutcomeAuditService disbursementOutcomeAuditService;

    @Mock private LoanApplication application;
    @Mock private Borrower borrower;
    @Mock private Lsp lsp;
    @Mock private LoanProduct loanProduct;
    @Mock private LoanProductVersion loanProductVersion;
    @Mock private LoanDisbursementRequestLog requestLog;

    private DisbursementOutcomeApplier applier;

    @BeforeEach
    void setUp() {
        applier = new DisbursementOutcomeApplier(
                loanDisbursementRequestLogRepository,
                loanAccountRepository,
                loanApplicationStatusWriter,
                webhookOutboxService,
                opsAlertEmitters,
                disbursementOutcomeAuditService,
                new ObjectMapper()
        );
    }

    private LoanAccount account(BigDecimal principal) {
        return new LoanAccount(
                application, borrower, lsp, loanProduct, loanProductVersion, "LMS-LN-TEST", principal, 12,
                LoanAccountStatus.DISBURSEMENT_REQUESTED, Instant.now()
        );
    }

    private LoanApplicationStatusTransitionCommand captureTransition() {
        ArgumentCaptor<LoanApplicationStatusTransitionCommand> captor =
                ArgumentCaptor.forClass(LoanApplicationStatusTransitionCommand.class);
        verify(loanApplicationStatusWriter).updateStatus(eq(application), captor.capture());
        return captor.getValue();
    }

    @Test
    void successDisbursesAccountPersistsFeeAndEmitsCompletionWebhook() {
        LoanAccount acct = account(new BigDecimal("150000"));
        when(requestLog.getAmount()).thenReturn(new BigDecimal("147750.00")); // net cash actually sent
        when(application.getLsp()).thenReturn(lsp);
        when(application.getId()).thenReturn(UUID.randomUUID());

        applier.apply(
                application, acct, requestLog,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE, "0", "RRN1", "ok"),
                "ops.admin", null, "corr-1"
        );

        assertEquals(LoanAccountStatus.DISBURSED, acct.getStatus());
        assertEquals(new BigDecimal("2250.00"), acct.getProcessingFeeAmount());
        assertEquals(LoanApplicationStatus.DISBURSED, captureTransition().targetStatus());
        verify(webhookOutboxService).enqueueIfSubscribed(
                eq(lsp), eq(WebhookEventType.DISBURSEMENT_COMPLETED), any(), any(), any(), any());
        verify(opsAlertEmitters, never()).emitLspBoundViolation(any(), any(), any(), any());
        verify(disbursementOutcomeAuditService).recordOutcomeApplied(
                eq(application), eq(acct), eq("ops.admin"), isNull(), eq("corr-1"),
                eq(DisbursementOutcomeAuditOutcome.DISBURSED), any());
    }

    @Test
    void businessDeclineRejectsApplicationAndAlertsOps() {
        LoanAccount acct = account(new BigDecimal("150000"));
        when(application.getLsp()).thenReturn(lsp);
        when(application.getId()).thenReturn(UUID.randomUUID());
        when(loanProductVersion.getProcessingFeeRate()).thenReturn(new BigDecimal("1.5"));

        applier.apply(
                application, acct, requestLog,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        DisbursementDisposition.FAILED, DisbursementDeclineKind.BUSINESS, "E01", "RRN2", "declined"),
                "worker", null, "corr-2"
        );

        assertEquals(LoanAccountStatus.DISBURSEMENT_FAILED, acct.getStatus());
        assertNull(acct.getProcessingFeeAmount(), "no processing fee is charged on a failed disbursement");
        LoanApplicationStatusTransitionCommand transition = captureTransition();
        assertEquals(LoanApplicationStatus.REJECTED, transition.targetStatus());
        assertEquals(LoanApplicationStatusReasonCode.FAILED_VERIFICATION, transition.reasonCode());
        assertEquals(LoanApplicationStatusTransitioner.TransitionContext.WORKER, transition.transitionContext());
        verify(opsAlertEmitters).emitLspBoundViolation(
                eq(application), eq("PROVIDER_BUSINESS_DECLINE"), any(), any());
    }

    @Test
    void technicalDeclineRetriesWithoutAlert() {
        LoanAccount acct = account(new BigDecimal("150000"));
        when(application.getLsp()).thenReturn(lsp);
        when(application.getId()).thenReturn(UUID.randomUUID());
        when(loanProductVersion.getProcessingFeeRate()).thenReturn(new BigDecimal("1.5"));

        applier.apply(
                application, acct, requestLog,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL, null, null, "timeout"),
                "worker", null, "corr-3"
        );

        assertEquals(LoanAccountStatus.DISBURSEMENT_FAILED, acct.getStatus());
        LoanApplicationStatusTransitionCommand transition = captureTransition();
        assertEquals(LoanApplicationStatus.DISBURSEMENT_RETRY, transition.targetStatus());
        assertEquals(LoanApplicationStatusReasonCode.POLICY_EXCEPTION, transition.reasonCode());
        verify(opsAlertEmitters, never()).emitLspBoundViolation(any(), any(), any(), any());
    }

    @Test
    void pendingParksForReconciliationAlertsAndSkipsWebhook() {
        LoanAccount acct = account(new BigDecimal("150000"));

        applier.apply(
                application, acct, requestLog,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        DisbursementDisposition.PENDING, DisbursementDeclineKind.NONE, null, null, "parked"),
                "worker", null, "corr-4"
        );

        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION, acct.getStatus());
        assertEquals(LoanApplicationStatus.DISBURSEMENT_RETRY, captureTransition().targetStatus());
        verify(opsAlertEmitters).emitLspBoundViolation(
                eq(application), eq("DISBURSEMENT_PENDING_RECONCILIATION"), any(), any());
        verify(webhookOutboxService, never()).enqueueIfSubscribed(any(), any(), any(), any(), any(), any());
    }
}
