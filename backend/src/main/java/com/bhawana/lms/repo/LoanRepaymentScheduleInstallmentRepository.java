package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepaymentScheduleInstallmentRepository extends JpaRepository<LoanRepaymentScheduleInstallment, UUID> {

    List<LoanRepaymentScheduleInstallment> findByLoanAccount_IdOrderByInstallmentNumberAsc(UUID loanAccountId);

    List<LoanRepaymentScheduleInstallment> findByLoanAccount_IdIn(Collection<UUID> loanAccountIds);

    java.util.Optional<LoanRepaymentScheduleInstallment> findByLoanAccount_IdAndInstallmentNumber(
            UUID loanAccountId,
            int installmentNumber
    );

    @Modifying(clearAutomatically = true)
    @Query("delete from LoanRepaymentScheduleInstallment i where i.loanAccount.id = :loanAccountId")
    long deleteByLoanAccountId(@Param("loanAccountId") UUID loanAccountId);
}
