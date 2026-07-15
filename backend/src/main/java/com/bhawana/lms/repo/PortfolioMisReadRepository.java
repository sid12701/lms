package com.bhawana.lms.repo;

import com.bhawana.lms.domain.LoanAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PortfolioMisReadRepository {

    private final EntityManager entityManager;

    public PortfolioMisReadRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public static final int EXPORT_BATCH_SIZE = 1_000;

    /**
     * Keyset page of loan account ids for MIS export. Ordered by {@code account.id} ascending so
     * callers can loop {@code lastExclusiveId = batch.getLast()} until an empty or short page.
     */
    public List<UUID> findAccountIdsForExportBatch(
            UUID lspId,
            Instant disbursalDateFrom,
            Instant disbursalDateTo,
            UUID lastExclusiveId,
            int limit
    ) {
        QuerySpec querySpec = buildQuerySpec(lspId, disbursalDateFrom, disbursalDateTo);
        String lastIdClause = lastExclusiveId == null ? "" : " and account.id > :lastExclusiveId";
        TypedQuery<UUID> query = entityManager.createQuery(
                """
                        select account.id
                        from LoanAccount account
                        join account.loanApplication application
                        join account.lsp lsp
                        """
                        + querySpec.whereClause()
                        + lastIdClause
                        + """
                         order by account.id asc
                        """,
                UUID.class
        );
        applyParameters(query, querySpec.parameters());
        if (lastExclusiveId != null) {
            query.setParameter("lastExclusiveId", lastExclusiveId);
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }

    public int findMaxInstallmentCountForExport(UUID lspId, Instant disbursalDateFrom, Instant disbursalDateTo) {
        QuerySpec querySpec = buildQuerySpec(lspId, disbursalDateFrom, disbursalDateTo);
        TypedQuery<Long> query = entityManager.createQuery(
                """
                        select coalesce(max(
                            (select count(inst)
                             from LoanRepaymentScheduleInstallment inst
                             where inst.loanAccount = account)
                        ), 0)
                        from LoanAccount account
                        join account.loanApplication application
                        join account.lsp lsp
                        """
                        + querySpec.whereClause(),
                Long.class
        );
        applyParameters(query, querySpec.parameters());
        Long result = query.getSingleResult();
        return result == null ? 0 : Math.toIntExact(result);
    }

    public List<LoanAccount> findAccountsByIds(List<UUID> accountIds) {
        return findDetailedAccounts(accountIds);
    }

    public PortfolioMisAccountPage findAccountsPage(
            UUID lspId,
            Instant disbursalDateFrom,
            Instant disbursalDateTo,
            int page,
            int size
    ) {
        QuerySpec querySpec = buildQuerySpec(lspId, disbursalDateFrom, disbursalDateTo);

        TypedQuery<UUID> idQuery = entityManager.createQuery(
                """
                        select account.id
                        from LoanAccount account
                        join account.loanApplication application
                        join account.lsp lsp
                        """
                        + querySpec.whereClause()
                        + """
                         order by lower(lsp.name) asc,
                                  case when account.disbursedAt is null then 1 else 0 end asc,
                                  account.disbursedAt desc,
                                  application.createdAt desc
                        """,
                UUID.class
        );
        applyParameters(idQuery, querySpec.parameters());
        idQuery.setFirstResult(page * size);
        idQuery.setMaxResults(size);
        List<UUID> accountIds = idQuery.getResultList();

        TypedQuery<Long> countQuery = entityManager.createQuery(
                """
                        select count(account)
                        from LoanAccount account
                        join account.loanApplication application
                        join account.lsp lsp
                        """
                        + querySpec.whereClause(),
                Long.class
        );
        applyParameters(countQuery, querySpec.parameters());

        return new PortfolioMisAccountPage(
                findDetailedAccounts(accountIds),
                countQuery.getSingleResult()
        );
    }

    private List<LoanAccount> findDetailedAccounts(List<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }

        TypedQuery<LoanAccount> detailQuery = entityManager.createQuery(
                """
                        select account
                        from LoanAccount account
                        join fetch account.loanApplication application
                        join fetch account.borrower borrower
                        join fetch account.lsp lsp
                        join fetch account.loanProduct product
                        join fetch account.loanProductVersion productVersion
                        where account.id in :accountIds
                        """,
                LoanAccount.class
        );
        detailQuery.setParameter("accountIds", accountIds);

        Map<UUID, LoanAccount> accountsById = new HashMap<>();
        for (LoanAccount account : detailQuery.getResultList()) {
            accountsById.put(account.getId(), account);
        }
        return accountIds.stream()
                .map(accountsById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public PortfolioMisSummaryAggregate summarize(
            UUID lspId,
            Instant disbursalDateFrom,
            Instant disbursalDateTo,
            LocalDate par30Cutoff
    ) {
        QuerySpec querySpec = buildQuerySpec(lspId, disbursalDateFrom, disbursalDateTo);
        TypedQuery<PortfolioMisSummaryAggregate> query = entityManager.createQuery(
                """
                        select new com.bhawana.lms.repo.PortfolioMisSummaryAggregate(
                            coalesce(sum(account.principalAmount), 0),
                            coalesce(sum(
                                case
                                    when application.status = com.bhawana.lms.domain.LoanApplicationStatus.UNDER_REPAYMENT
                                      or application.status = com.bhawana.lms.domain.LoanApplicationStatus.DISBURSED
                                    then 1
                                    else 0
                                end
                            ), 0),
                            coalesce(sum(
                                case
                                    when product.interestRate is not null and product.interestRate > 0
                                    then account.principalAmount * product.interestRate
                                    else 0
                                end
                            ), 0),
                            coalesce(sum(
                                case
                                    when product.interestRate is not null and product.interestRate > 0
                                    then account.principalAmount
                                    else 0
                                end
                            ), 0),
                            coalesce(sum(
                                case
                                    when exists (
                                        select 1
                                        from LoanRepaymentScheduleInstallment installment
                                        where installment.loanAccount = account
                                          and installment.outstandingAmount > 0
                                          and installment.dueDate < :par30Cutoff
                                    )
                                    then account.principalAmount
                                    else 0
                                end
                            ), 0),
                            count(account)
                        )
                        from LoanAccount account
                        join account.loanApplication application
                        join account.loanProduct product
                        join account.lsp lsp
                        """
                        + querySpec.whereClause(),
                PortfolioMisSummaryAggregate.class
        );
        applyParameters(query, querySpec.parameters());
        query.setParameter("par30Cutoff", par30Cutoff);
        return query.getSingleResult();
    }

    private static <T> void applyParameters(TypedQuery<T> query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
    }

    private static QuerySpec buildQuerySpec(UUID lspId, Instant disbursalDateFrom, Instant disbursalDateTo) {
        StringBuilder whereClause = new StringBuilder(" where 1 = 1");
        Map<String, Object> parameters = new LinkedHashMap<>();

        if (lspId != null) {
            whereClause.append(" and lsp.id = :lspId");
            parameters.put("lspId", lspId);
        }
        if (disbursalDateFrom != null) {
            whereClause.append(" and account.disbursedAt >= :disbursalDateFrom");
            parameters.put("disbursalDateFrom", disbursalDateFrom);
        }
        if (disbursalDateTo != null) {
            whereClause.append(" and account.disbursedAt < :disbursalDateTo");
            parameters.put("disbursalDateTo", disbursalDateTo);
        }

        return new QuerySpec(whereClause.toString(), parameters);
    }

    public record PortfolioMisAccountPage(List<LoanAccount> content, long totalElements) {
    }

    private record QuerySpec(String whereClause, Map<String, Object> parameters) {
    }
}
