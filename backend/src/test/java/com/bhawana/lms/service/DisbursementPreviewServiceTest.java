package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DisbursementPreviewServiceTest {

    @Mock private LoanApplicationQueryService loanApplicationQueryService;
    @Mock private LoanAccountRepository loanAccountRepository;
    @Mock private DisbursementPaymentModeSelector paymentModeSelector;
    @Mock private DisbursementIntentRepository disbursementIntentRepository;
    @Mock private LoanApplication application;

    @Test
    void previewDoesNotRepairMissingLoanAccountState() {
        UUID applicationId = UUID.randomUUID();
        when(loanApplicationQueryService.getApplication(applicationId)).thenReturn(application);
        when(application.getStatus()).thenReturn(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
        when(loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)).thenReturn(Optional.empty());

        DisbursementPreviewService service = new DisbursementPreviewService(
                loanApplicationQueryService,
                loanAccountRepository,
                paymentModeSelector,
                disbursementIntentRepository
        );

        BusinessRuleViolationException exception = assertThrows(
                BusinessRuleViolationException.class,
                () -> service.buildPreview(applicationId)
        );

        assertEquals("LOAN_ACCOUNT_MISSING", exception.getErrorCode());
    }
}
