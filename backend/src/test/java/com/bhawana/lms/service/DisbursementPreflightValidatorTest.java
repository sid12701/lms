package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DisbursementPreflightValidatorTest {

    @Mock
    private LoanApplicationQueryService loanApplicationQueryService;

    @Mock
    private LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService;

    @Mock
    private LoanRepaymentScheduleService loanRepaymentScheduleService;

    @Mock
    private BorrowerBankDetailsService borrowerBankDetailsService;

    @Mock
    private BankAccountHolderNameMatcher holderNameMatcher;

    private DisbursementPreflightValidator disbursementPreflightValidator;

    @BeforeEach
    void setUp() {
        disbursementPreflightValidator = new DisbursementPreflightValidator(
                loanApplicationQueryService,
                loanApplicationDocumentChecklistService,
                loanRepaymentScheduleService,
                borrowerBankDetailsService,
                holderNameMatcher
        );
    }

    @Test
    void ensureDisbursementRequestAllowedRejectsDuplicateRequest() {
        LoanAccount loanAccount = mock(LoanAccount.class);
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.DISBURSEMENT_REQUESTED);

        ApiConflictException exception = assertThrows(
                ApiConflictException.class,
                () -> disbursementPreflightValidator.ensureDisbursementRequestAllowed(loanAccount)
        );

        assertEquals("DISBURSEMENT_ALREADY_REQUESTED", exception.getErrorCode());
    }

    @Test
    void ensureDisbursementRequestAllowedAcceptsPendingDisbursement() {
        LoanAccount loanAccount = mock(LoanAccount.class);
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.PENDING_DISBURSEMENT);

        disbursementPreflightValidator.ensureDisbursementRequestAllowed(loanAccount);
    }
}
