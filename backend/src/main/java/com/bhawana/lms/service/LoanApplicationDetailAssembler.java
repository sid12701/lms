package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationDetailAssembler {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanApplicationServicingReadService loanApplicationServicingReadService;
    private final BusinessCalendar businessCalendar;

    public LoanApplicationDetailAssembler(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanApplicationServicingReadService loanApplicationServicingReadService,
            BusinessCalendar businessCalendar
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanApplicationServicingReadService = loanApplicationServicingReadService;
        this.businessCalendar = businessCalendar;
    }

    @Transactional(readOnly = true)
    public LoanApplicationDetailView getDetail(UUID applicationId) {
        LoanApplication application = loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));

        Optional<LoanAccount> loanAccount = loanAccountRepository.findDetailedByLoanApplication_Id(applicationId);
        List<LoanRepaymentScheduleInstallment> installments = loanAccount
                .map(account -> loanRepaymentScheduleInstallmentRepository
                        .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()))
                .orElse(List.of());

        Optional<LoanRepaymentScheduleSummary> repaymentScheduleSummary =
                LoanRepaymentScheduleSummary.fromInstallments(installments);
        Optional<LoanDelinquencySummary> delinquencySummary =
                LoanDelinquencySupport.summarize(installments, businessCalendar.today());
        Optional<LoanApplicationLastActivity> lastActivity =
                loanApplicationServicingReadService.getLatestActivity(applicationId);
        Optional<LoanDisbursementRequestLog> latestDisbursementRequest = loanAccount
                .flatMap(account -> loanApplicationServicingReadService.getLatestDisbursementRequest(account.getId()));

        return new LoanApplicationDetailView(
                application,
                loanAccount,
                lastActivity,
                repaymentScheduleSummary,
                delinquencySummary,
                latestDisbursementRequest
        );
    }

    public record LoanApplicationDetailView(
            LoanApplication application,
            Optional<LoanAccount> loanAccount,
            Optional<LoanApplicationLastActivity> lastActivity,
            Optional<LoanRepaymentScheduleSummary> repaymentScheduleSummary,
            Optional<LoanDelinquencySummary> delinquencySummary,
            Optional<LoanDisbursementRequestLog> latestDisbursementRequest
    ) {
    }
}
