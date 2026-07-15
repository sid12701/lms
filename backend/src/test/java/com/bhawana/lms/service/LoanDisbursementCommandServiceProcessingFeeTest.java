package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class LoanDisbursementCommandServiceProcessingFeeTest {

    @Mock private LoanApplicationRepository loanApplicationRepository;
    @Mock private LoanAccountRepository loanAccountRepository;
    @Mock private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Mock private LoanDisbursementAdapter loanDisbursementAdapter;
    @Mock private WebhookOutboxService webhookOutboxService;
    @Mock private LoanApplicationQueryService loanApplicationQueryService;
    @Mock private LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService;
    @Mock private LoanApplicationStatusWriter loanApplicationStatusWriter;
    @Mock private DisbursementOutcomeAuditService disbursementOutcomeAuditService;
    @Mock private OpsAlertEmitters opsAlertEmitters;
    @Mock private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Mock private TransactionTemplate transactionTemplate;

    @Mock private LoanApplication application;
    @Mock private Borrower borrower;
    @Mock private Lsp lsp;
    @Mock private LoanProduct loanProduct;
    @Mock private LoanProductVersion loanProductVersion;

    private LoanDisbursementCommandService service;
    private final UUID applicationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        DisbursementOutcomeApplier disbursementOutcomeApplier = new DisbursementOutcomeApplier(
                loanDisbursementRequestLogRepository,
                loanAccountRepository,
                loanApplicationStatusWriter,
                webhookOutboxService,
                opsAlertEmitters,
                disbursementOutcomeAuditService,
                new ObjectMapper()
        );
        DisbursementIntentWorkflowProperties intentWorkflowProperties = new DisbursementIntentWorkflowProperties();
        intentWorkflowProperties.setEnabled(false);
        LoanDisbursementMockProperties mockProperties = new LoanDisbursementMockProperties();
        service = new LoanDisbursementCommandService(
                loanApplicationRepository,
                loanAccountRepository,
                loanDisbursementRequestLogRepository,
                loanDisbursementAdapter,
                webhookOutboxService,
                loanApplicationQueryService,
                loanApplicationDocumentChecklistService,
                loanApplicationStatusWriter,
                disbursementOutcomeApplier,
                mockProperties,
                disbursementIntentWorkflowService,
                intentWorkflowProperties,
                new DisbursementPaymentModeSelector(mockProperties),
                transactionTemplate,
                new ObjectMapper()
        );
    }

    private LoanAccount account(BigDecimal principal, LoanAccountStatus status) {
        return new LoanAccount(
                application, borrower, lsp, loanProduct, loanProductVersion, "LMS-LN-TEST", principal, 12, status, Instant.now()
        );
    }

    private void stubLockAndResolve(LoanAccount acct) {
        when(loanApplicationRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(application));
        when(loanApplicationQueryService.getApplication(applicationId)).thenReturn(application);
        when(application.getId()).thenReturn(applicationId);
        when(loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)).thenReturn(Optional.of(acct));
        when(application.getStatus()).thenReturn(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
    }

    @Test
    void initiateDisbursementSendsPrincipalMinusFeeToAdapter() {
        stubLockAndResolve(account(new BigDecimal("150000"), LoanAccountStatus.PENDING_DISBURSEMENT));
        when(loanProductVersion.getProcessingFeeRate()).thenReturn(new BigDecimal("1.5"));
        when(application.getBorrower()).thenReturn(borrower);
        when(application.getExternalLoanId()).thenReturn("EXT-1");
        when(application.getLsp()).thenReturn(lsp);
        when(borrower.getFullName()).thenReturn("Asha Borrower");
        when(lsp.getCode()).thenReturn("LSP-A");
        when(loanDisbursementAdapter.requestDisbursement(any())).thenReturn(
                new LoanDisbursementAdapter.DisbursementResult(
                        "MOCK_ICICI", "req-1", "SUCCESS",
                        DisbursementPaymentMode.IMPS, DisbursementDisposition.SUCCESS,
                        DisbursementDeclineKind.NONE, "0", "401319578626",
                        "Transaction Successful", "{}"
                )
        );

        service.initiateDisbursement(applicationId, "ops.admin");

        ArgumentCaptor<LoanDisbursementAdapter.DisbursementCommand> captor =
                ArgumentCaptor.forClass(LoanDisbursementAdapter.DisbursementCommand.class);
        verify(loanDisbursementAdapter).requestDisbursement(captor.capture());
        // 150000 - (150000 * 1.5%) = 150000 - 2250 = 147750.00 net cash to borrower
        assertEquals(new BigDecimal("147750.00"), captor.getValue().amount());
    }

    @Test
    void initiateDisbursementRejectsWhenFeeWouldLeaveNoNetCash() {
        stubLockAndResolve(account(new BigDecimal("150000"), LoanAccountStatus.PENDING_DISBURSEMENT));
        when(loanProductVersion.getProcessingFeeRate()).thenReturn(new BigDecimal("100"));

        assertThrows(
                BusinessRuleViolationException.class,
                () -> service.initiateDisbursement(applicationId, "ops.admin")
        );
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());
    }

    @Test
    void resolveMockOutcomeDisbursedPersistsActualProcessingFee() {
        LoanAccount acct = account(new BigDecimal("150000"), LoanAccountStatus.DISBURSEMENT_REQUESTED);
        when(loanApplicationQueryService.getApplication(applicationId)).thenReturn(application);
        when(loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)).thenReturn(Optional.of(acct));
        when(application.getLsp()).thenReturn(lsp);
        when(application.getId()).thenReturn(applicationId);
        LoanDisbursementRequestLog requestLog = mock(LoanDisbursementRequestLog.class);
        when(requestLog.getAmount()).thenReturn(new BigDecimal("147750.00")); // net actually sent
        when(loanDisbursementRequestLogRepository.findTopByLoanAccount_IdOrderByCreatedAtDesc(acct.getId()))
                .thenReturn(Optional.of(requestLog));

        service.resolveMockDisbursementOutcome(applicationId, "ops.admin", MockDisbursementOutcome.DISBURSED);

        // persisted fee = principal - net actually disbursed = 150000 - 147750.00 = 2250.00
        assertEquals(new BigDecimal("2250.00"), acct.getProcessingFeeAmount());
        assertEquals(LoanAccountStatus.DISBURSED, acct.getStatus());
    }
}
