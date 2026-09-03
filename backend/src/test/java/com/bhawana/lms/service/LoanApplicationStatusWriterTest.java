package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanApplicationStatusWriterTest {

    @Mock
    private LoanAccountRepository loanAccountRepository;

    @Mock
    private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;

    @Mock
    private LoanApplicationRepository loanApplicationRepository;

    @Mock
    private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;

    @Mock
    private BorrowerActiveLoanChecker borrowerActiveLoanChecker;

    @Mock
    private LoanRepaymentScheduleService loanRepaymentScheduleService;

    @Mock
    private LoanEventLog loanEventLog;

    @Mock
    private LoanApplication application;

    @Mock
    private Borrower borrower;

    private LoanApplicationStatusWriter writer;

    @BeforeEach
    void setUp() {
        writer = new LoanApplicationStatusWriter(
                loanAccountRepository,
                loanApplicationAuditEventRepository,
                loanApplicationRepository,
                loanApplicationStatusTransitionRepository,
                borrowerActiveLoanChecker,
                loanRepaymentScheduleService,
                loanEventLog
        );
    }

    @Test
    void refusesToCreateSecondOpenAccountAfterAcquiringBorrowerLock() {
        UUID applicationId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        when(application.getId()).thenReturn(applicationId);
        when(application.getBorrower()).thenReturn(borrower);
        when(borrower.getId()).thenReturn(borrowerId);
        when(loanApplicationRepository.findBorrowerByApplicationIdForUpdate(applicationId))
                .thenReturn(Optional.of(borrower));
        when(loanAccountRepository.findByLoanApplication_Id(applicationId)).thenReturn(Optional.empty());
        when(borrowerActiveLoanChecker.hasOpenLoanAcrossAllLsps(borrowerId)).thenReturn(true);

        assertThatThrownBy(() -> writer.ensureLoanAccountForApprovedApplication(application))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(exception -> assertThat(((BusinessRuleViolationException) exception).getErrorCode())
                        .isEqualTo("BORROWER_HAS_OPEN_LOAN"));

        verify(loanApplicationRepository).findBorrowerByApplicationIdForUpdate(applicationId);
        verify(loanAccountRepository, never()).save(any());
        verify(loanRepaymentScheduleService, never()).generateIfAbsent(any());
    }

    @Test
    void returnsExistingAccountIdempotentlyWithoutTreatingItAsAnotherOpenLoan() {
        UUID applicationId = UUID.randomUUID();
        LoanAccount existingAccount = org.mockito.Mockito.mock(LoanAccount.class);
        when(application.getId()).thenReturn(applicationId);
        when(loanApplicationRepository.findBorrowerByApplicationIdForUpdate(applicationId))
                .thenReturn(Optional.of(borrower));
        when(loanAccountRepository.findByLoanApplication_Id(applicationId))
                .thenReturn(Optional.of(existingAccount));

        LoanAccount result = writer.ensureLoanAccountForApprovedApplication(application);

        assertThat(result).isSameAs(existingAccount);
        verify(borrowerActiveLoanChecker, never()).hasOpenLoanAcrossAllLsps(any());
        verify(loanAccountRepository, never()).save(any());
        verify(loanRepaymentScheduleService).generateIfAbsent(existingAccount);
    }
}
