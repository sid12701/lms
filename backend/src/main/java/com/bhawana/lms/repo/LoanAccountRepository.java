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
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanAccountRepository extends JpaRepository<LoanAccount, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from LoanAccount account where account.id = :id")
    Optional<LoanAccount> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from LoanAccount account where account.loanApplication.id = :applicationId")
    Optional<LoanAccount> findByLoanApplication_IdForUpdate(@Param("applicationId") UUID applicationId);

    @EntityGraph(attributePaths = {
            "loanApplication",
            "loanApplication.borrower",
            "loanApplication.lsp",
            "loanApplication.loanProduct",
            "loanApplication.loanProductVersion",
            "borrower",
            "lsp",
            "loanProduct",
            "loanProductVersion"
    })
    Optional<LoanAccount> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {
            "loanApplication",
            "loanApplication.borrower",
            "loanApplication.lsp",
            "loanApplication.loanProduct",
            "loanApplication.loanProductVersion",
            "borrower",
            "lsp",
            "loanProduct",
            "loanProductVersion"
    })
    Optional<LoanAccount> findDetailedByLoanApplication_Id(UUID applicationId);

    @EntityGraph(attributePaths = {"loanApplication"})
    Optional<LoanAccount> findByLoanApplication_Id(UUID applicationId);

    List<LoanAccount> findByStatus(LoanAccountStatus status);

    /**
     * Bounded discovery for the reconciliation sweep: in-flight or parked accounts that
     * already submitted at least one provider call (a stored request row exists) but hold no
     * queue entry yet — typically legacy evidence. Fresh CREATED intents with no submitted
     * call are excluded: the worker still owns their first execution, and queueing them as
     * mismatches would fabricate a problem that does not exist.
     */
    @Query("""
            select account.id
            from LoanAccount account
            where account.status in (
                com.bhawana.lms.domain.LoanAccountStatus.DISBURSEMENT_REQUESTED,
                com.bhawana.lms.domain.LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION
            )
              and exists (
                  select 1 from LoanDisbursementRequestLog requestLog
                  where requestLog.loanAccount = account
              )
              and not exists (
                  select 1 from DisbursementReconciliationQueueEntry queueEntry
                  where queueEntry.loanAccount = account
              )
            order by account.createdAt asc
            """)
    List<UUID> findUnqueuedSubmittedIds(org.springframework.data.domain.Pageable pageable);

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

    /**
     * The duplicate-loan alert serializes lsp/application details after the
     * admin read transaction has closed, so those associations must be fetched eagerly here.
     */
    @EntityGraph(attributePaths = {"lsp", "loanApplication"})
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
            select application.id as applicationId,
                   application.externalLoanId as externalLoanId,
                   borrower.fullName as customerName,
                   lsp.code as lspCode,
                   account.principalAmount as principalAmount,
                   productVersion.interestRate as interestRate,
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
            join account.loanProductVersion productVersion
            left join LoanRepaymentScheduleInstallment installment on installment.loanAccount = account
            group by account.id,
                     application.id,
                     application.externalLoanId,
                     borrower.fullName,
                     lsp.code,
                     account.principalAmount,
                     productVersion.interestRate,
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
