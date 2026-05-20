package com.bhawana.lms.repo;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class LoanApplicationReadRepository {

    private final EntityManager entityManager;

    public LoanApplicationReadRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public PagedResult<LoanApplication> findApplications(
            UUID lspId,
            UUID productId,
            LoanApplicationStatus status,
            String sourceChannel,
            String queryText,
            UUID queryApplicationId,
            Instant disbursalDateFrom,
            Instant disbursalDateTo
    ) {
        return findApplications(
                lspId,
                productId,
                status,
                sourceChannel,
                queryText,
                queryApplicationId,
                disbursalDateFrom,
                disbursalDateTo,
                false,
                0,
                0,
                false
        );
    }

    public PagedResult<LoanApplication> findApplications(
            UUID lspId,
            UUID productId,
            LoanApplicationStatus status,
            String sourceChannel,
            String queryText,
            UUID queryApplicationId,
            Instant disbursalDateFrom,
            Instant disbursalDateTo,
            boolean paginationRequested,
            int offset,
            int limit,
            boolean includePaginationDetails
    ) {
        StringBuilder filters = new StringBuilder();
        Map<String, Object> parameters = new LinkedHashMap<>();
        appendFilters(
                filters,
                parameters,
                lspId,
                productId,
                status,
                sourceChannel,
                queryText,
                queryApplicationId,
                disbursalDateFrom,
                disbursalDateTo
        );

        String accountJoin = needsAccountJoin(disbursalDateFrom, disbursalDateTo)
                ? " join LoanAccount account on account.loanApplication = application\n"
                : "";

        String selectJpql = """
                select application
                from LoanApplication application
                join fetch application.borrower borrower
                join fetch application.lsp lsp
                join fetch application.loanProduct product
                """ + accountJoin + """
                where 1 = 1
                """ + filters + """
                 order by application.createdAt desc
                """;

        TypedQuery<LoanApplication> query = entityManager.createQuery(selectJpql, LoanApplication.class);
        parameters.forEach(query::setParameter);
        if (paginationRequested) {
            query.setFirstResult(offset);
            query.setMaxResults(limit);
        }
        List<LoanApplication> applications = query.getResultList();

        long totalCount = includePaginationDetails
                ? countApplications(filters.toString(), parameters)
                : applications.size();
        int pageOffset = paginationRequested ? offset : 0;
        int pageLimit = paginationRequested ? limit : applications.size();
        return new PagedResult<>(applications, totalCount, pageOffset, pageLimit);
    }

    private long countApplications(String filters, Map<String, Object> parameters) {
        String countJpql = """
                select count(distinct application.id)
                from LoanApplication application
                join application.borrower borrower
                join application.lsp lsp
                join application.loanProduct product
                """ + (filters.contains("account.") ? " join LoanAccount account on account.loanApplication = application\n" : "") + """
                where 1 = 1
                """ + filters;
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        parameters.forEach(countQuery::setParameter);
        return countQuery.getSingleResult();
    }

    private static boolean needsAccountJoin(Instant disbursalDateFrom, Instant disbursalDateTo) {
        return disbursalDateFrom != null || disbursalDateTo != null;
    }

    private static void appendFilters(
            StringBuilder jpql,
            Map<String, Object> parameters,
            UUID lspId,
            UUID productId,
            LoanApplicationStatus status,
            String sourceChannel,
            String queryText,
            UUID queryApplicationId,
            Instant disbursalDateFrom,
            Instant disbursalDateTo
    ) {
        if (lspId != null) {
            jpql.append(" and lsp.id = :lspId");
            parameters.put("lspId", lspId);
        }
        if (productId != null) {
            jpql.append(" and product.id = :productId");
            parameters.put("productId", productId);
        }
        if (status != null) {
            jpql.append(" and application.status = :status");
            parameters.put("status", status);
        }
        if (sourceChannel != null) {
            jpql.append(" and upper(application.sourceChannel) = :sourceChannel");
            parameters.put("sourceChannel", sourceChannel.toUpperCase(Locale.ROOT));
        }
        if (queryText != null) {
            jpql.append("""
                     and (
                        lower(borrower.fullName) like :queryText
                        or lower(borrower.pan) like :queryText
                        or lower(borrower.mobile) like :queryText
                        or lower(coalesce(borrower.city, '')) like :queryText
                        or lower(coalesce(borrower.state, '')) like :queryText
                        or lower(coalesce(borrower.employmentType, '')) like :queryText
                        or lower(application.externalLoanId) like :queryText
                    """);
            parameters.put("queryText", "%" + queryText.toLowerCase(Locale.ROOT) + "%");

            if (queryApplicationId != null) {
                jpql.append(" or application.id = :queryApplicationId");
                parameters.put("queryApplicationId", queryApplicationId);
            }

            jpql.append(")");
        }
        if (disbursalDateFrom != null) {
            jpql.append(" and account.disbursedAt >= :disbursalDateFrom");
            parameters.put("disbursalDateFrom", disbursalDateFrom);
        }
        if (disbursalDateTo != null) {
            jpql.append(" and account.disbursedAt < :disbursalDateTo");
            parameters.put("disbursalDateTo", disbursalDateTo);
        }
    }
}
