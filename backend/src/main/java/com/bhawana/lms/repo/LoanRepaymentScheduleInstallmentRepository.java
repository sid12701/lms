package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepaymentScheduleInstallmentRepository extends JpaRepository<LoanRepaymentScheduleInstallment, UUID> {

    List<LoanRepaymentScheduleInstallment> findByLoanAccount_IdOrderByInstallmentNumberAsc(UUID loanAccountId);

    List<LoanRepaymentScheduleInstallment> findByLoanAccount_IdIn(Collection<UUID> loanAccountIds);

    Optional<LoanRepaymentScheduleInstallment> findByLoanAccount_IdAndInstallmentNumber(
            UUID loanAccountId,
            int installmentNumber
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "loanAccount")
    @Query("select i from LoanRepaymentScheduleInstallment i where i.id = :id")
    Optional<LoanRepaymentScheduleInstallment> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "loanAccount")
    @Query("select i from LoanRepaymentScheduleInstallment i where i.loanAccount.id = :loanAccountId and i.installmentNumber = :installmentNumber")
    Optional<LoanRepaymentScheduleInstallment> findByAccountAndNumberForUpdate(
            @Param("loanAccountId") UUID loanAccountId,
            @Param("installmentNumber") int installmentNumber
    );

    @Modifying(clearAutomatically = true)
    @Query("delete from LoanRepaymentScheduleInstallment i where i.loanAccount.id = :loanAccountId")
    long deleteByLoanAccountId(@Param("loanAccountId") UUID loanAccountId);
}
