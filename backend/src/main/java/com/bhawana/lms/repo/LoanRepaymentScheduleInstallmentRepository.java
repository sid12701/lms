package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepaymentScheduleInstallmentRepository extends JpaRepository<LoanRepaymentScheduleInstallment, UUID> {

    List<LoanRepaymentScheduleInstallment> findByLoanAccount_IdOrderByInstallmentNumberAsc(UUID loanAccountId);
}
