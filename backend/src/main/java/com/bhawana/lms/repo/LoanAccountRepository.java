package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanAccountRepository extends JpaRepository<LoanAccount, UUID> {

    @EntityGraph(attributePaths = {
            "loanApplication",
            "loanApplication.borrower",
            "loanApplication.lsp",
            "loanApplication.loanProduct",
            "borrower",
            "lsp",
            "loanProduct"
    })
    Optional<LoanAccount> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {
            "loanApplication",
            "loanApplication.borrower",
            "loanApplication.lsp",
            "loanApplication.loanProduct",
            "borrower",
            "lsp",
            "loanProduct"
    })
    Optional<LoanAccount> findDetailedByLoanApplication_Id(UUID applicationId);

    @EntityGraph(attributePaths = {
            "loanApplication",
            "loanApplication.borrower",
            "loanApplication.lsp",
            "loanApplication.loanProduct",
            "borrower",
            "lsp",
            "loanProduct"
    })
    List<LoanAccount> findDetailedBy();

    Optional<LoanAccount> findByLoanApplication_Id(UUID applicationId);

    @Query("""
            select account.loanApplication.id as applicationId,
                   account.accountNumber as accountNumber
            from LoanAccount account
            where account.loanApplication.id in :applicationIds
            """)
    List<LoanAccountNumberProjection> findAccountNumbersByLoanApplicationIdIn(
            @Param("applicationIds") Collection<UUID> applicationIds
    );

    boolean existsByBorrower_IdAndStatusIn(UUID borrowerId, Collection<LoanAccountStatus> statuses);

    List<LoanAccount> findByBorrower_IdAndStatusIn(UUID borrowerId, Collection<LoanAccountStatus> statuses);

    @EntityGraph(attributePaths = {
            "loanApplication",
            "loanApplication.borrower",
            "loanApplication.lsp",
            "loanApplication.loanProduct",
            "borrower",
            "lsp",
            "loanProduct"
    })
    List<LoanAccount> findDetailedByBorrower_Id(UUID borrowerId);

    @Query("""
            select account.lsp.id as lspId,
                   coalesce(sum(case when account.disbursedAt is not null then 1 else 0 end), 0) as disbursedLoanCount,
                   coalesce(sum(case when account.disbursedAt is not null then account.principalAmount else 0 end), 0) as totalDisbursedAmount,
                   max(account.disbursedAt) as latestDisbursalAt
            from LoanAccount account
            group by account.lsp.id
            """)
    List<LspAccountSummaryProjection> summarizeAccountsByLsp();

    @Query("""
            select account.lsp.id as lspId,
                   coalesce(sum(case when account.disbursedAt is not null then 1 else 0 end), 0) as disbursedLoanCount,
                   coalesce(sum(case when account.disbursedAt is not null then account.principalAmount else 0 end), 0) as totalDisbursedAmount,
                   max(account.disbursedAt) as latestDisbursalAt
            from LoanAccount account
            where account.lsp.id = :lspId
            group by account.lsp.id
            """)
    Optional<LspAccountSummaryProjection> summarizeAccountsForLsp(@Param("lspId") UUID lspId);

    @Query("""
            select account.lsp.id as lspId,
                   case when account.disbursedAt is null then 0 else account.principalAmount end as disbursedAmount,
                   coalesce(sum(installment.outstandingAmount), 0) as outstandingAmount,
                   min(case
                       when installment.outstandingAmount > 0 and installment.dueDate < :today
                       then installment.dueDate
                       else null
                   end) as oldestOverdueDueDate
            from LoanAccount account
            left join LoanRepaymentScheduleInstallment installment on installment.loanAccount = account
            group by account.id, account.lsp.id, account.disbursedAt, account.principalAmount
            """)
    List<HomeDashboardAccountSnapshotProjection> findHomeDashboardAccountSnapshots(@Param("today") LocalDate today);

    @Query("""
            select application.id as applicationId,
                   application.externalLoanId as externalLoanId,
                   borrower.fullName as customerName,
                   lsp.code as lspCode,
                   account.principalAmount as principalAmount,
                   product.interestRate as interestRate,
                   application.status as loanStatus,
                   coalesce(sum(case
                       when installment.outstandingAmount > 0 and installment.dueDate < :today
                       then installment.outstandingAmount
                       else 0
                   end), 0) as overdueAmount,
                   min(case
                       when installment.outstandingAmount > 0 and installment.dueDate < :today
                       then installment.dueDate
                       else null
                   end) as oldestOverdueDueDate
            from LoanAccount account
            join account.loanApplication application
            join account.borrower borrower
            join account.lsp lsp
            join account.loanProduct product
            left join LoanRepaymentScheduleInstallment installment on installment.loanAccount = account
            group by account.id,
                     application.id,
                     application.externalLoanId,
                     borrower.fullName,
                     lsp.code,
                     account.principalAmount,
                     product.interestRate,
                     application.status,
                     account.createdAt
            order by case
                         when min(case
                             when installment.outstandingAmount > 0 and installment.dueDate < :today
                             then installment.dueDate
                             else null
                         end) is null
                         then 1
                         else 0
                     end asc,
                     min(case
                         when installment.outstandingAmount > 0 and installment.dueDate < :today
                         then installment.dueDate
                         else null
                     end) asc,
                     coalesce(sum(case
                         when installment.outstandingAmount > 0 and installment.dueDate < :today
                         then installment.outstandingAmount
                         else 0
                     end), 0) desc,
                     account.createdAt desc
            """)
    List<HomeDashboardPriorityAccountProjection> findHomeDashboardPriorityAccounts(
            @Param("today") LocalDate today,
            Pageable pageable
    );

    interface LspAccountSummaryProjection {
        UUID getLspId();

        long getDisbursedLoanCount();

        BigDecimal getTotalDisbursedAmount();

        Instant getLatestDisbursalAt();
    }

    interface LoanAccountNumberProjection {
        UUID getApplicationId();

        String getAccountNumber();
    }

    interface HomeDashboardAccountSnapshotProjection {
        UUID getLspId();

        BigDecimal getDisbursedAmount();

        BigDecimal getOutstandingAmount();

        LocalDate getOldestOverdueDueDate();
    }

    interface HomeDashboardPriorityAccountProjection {
        UUID getApplicationId();

        String getExternalLoanId();

        String getCustomerName();

        String getLspCode();

        BigDecimal getPrincipalAmount();

        BigDecimal getInterestRate();

        LoanApplicationStatus getLoanStatus();

        BigDecimal getOverdueAmount();

        LocalDate getOldestOverdueDueDate();
    }
}
