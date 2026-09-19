package com.bhawana.lms.repo;

import com.bhawana.lms.common.api.PagedResult;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class LoanApplicationReadRepository {

    /**
     * H29 — sortable columns the ops triage list may order by. Anything else
     * is rejected by the query service before it reaches JPQL, so the order
     * clause below is never built from raw request input.
     */
    public static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt",
            "updatedAt",
            "requestedAmount",
            "status"
    );

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
                null,
                null,
                disbursalDateFrom,
                disbursalDateTo
        );
    }

    public PagedResult<LoanApplication> findApplications(
            UUID lspId,
            UUID productId,
            LoanApplicationStatus status,
            String sourceChannel,
            String queryText,
            UUID queryApplicationId,
            String lspLoanIdText,
            String bhawLoanIdText,
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
                lspLoanIdText,
                bhawLoanIdText,
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
            String lspLoanIdText,
            String bhawLoanIdText,
            Instant disbursalDateFrom,
            Instant disbursalDateTo,
            boolean paginationRequested,
            int offset,
            int limit,
            boolean includePaginationDetails
    ) {
        return findApplications(
                lspId,
                productId,
                status == null ? Set.of() : Set.of(status),
                sourceChannel,
                queryText,
                queryApplicationId,
                lspLoanIdText,
                bhawLoanIdText,
                disbursalDateFrom,
                disbursalDateTo,
                "createdAt",
                true,
                paginationRequested,
                offset,
                limit,
                includePaginationDetails
        );
    }

    /**
     * H29 — multi-status (OR) + validated server sort. Every selected status
     * filters with OR semantics, and ordering applies a stable id tie-breaker
     * before pagination so rows cannot slip between pages.
     */
    public PagedResult<LoanApplication> findApplications(
            UUID lspId,
            UUID productId,
            Set<LoanApplicationStatus> statuses,
            String sourceChannel,
            String queryText,
            UUID queryApplicationId,
            String lspLoanIdText,
            String bhawLoanIdText,
            Instant disbursalDateFrom,
            Instant disbursalDateTo,
            String sortBy,
            boolean sortDescending,
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
                statuses,
                sourceChannel,
                queryText,
                queryApplicationId,
                lspLoanIdText,
                bhawLoanIdText,
                disbursalDateFrom,
                disbursalDateTo
        );

        String accountJoin = needsAccountJoin(bhawLoanIdText, disbursalDateFrom, disbursalDateTo)
                ? " join LoanAccount account on account.loanApplication = application\n"
                : "";

        String selectJpql = """
                select application
                from LoanApplication application
                join fetch application.borrower borrower
                join fetch application.lsp lsp
                join fetch application.loanProduct product
                join fetch application.loanProductVersion productVersion
                """ + accountJoin + """
                where 1 = 1
                """ + filters + """
                """ + orderClause(sortBy, sortDescending) + """
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

    private static String orderClause(String sortBy, boolean sortDescending) {
        String field = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        String direction = sortDescending ? "desc" : "asc";
        // Stable id tie-breaker before pagination: rows sharing the primary
        // key cannot drift between pages across requests.
        return " order by application." + field + " " + direction + ", application.id asc\n";
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

    private static boolean needsAccountJoin(
            String bhawLoanIdText,
            Instant disbursalDateFrom,
            Instant disbursalDateTo
    ) {
        return bhawLoanIdText != null || disbursalDateFrom != null || disbursalDateTo != null;
    }

    private static void appendFilters(
            StringBuilder jpql,
            Map<String, Object> parameters,
            UUID lspId,
            UUID productId,
            Set<LoanApplicationStatus> statuses,
            String sourceChannel,
            String queryText,
            UUID queryApplicationId,
            String lspLoanIdText,
            String bhawLoanIdText,
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
        if (statuses != null && !statuses.isEmpty()) {
            jpql.append(" and application.status in :statuses");
            parameters.put("statuses", statuses);
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
        if (lspLoanIdText != null) {
            jpql.append(" and lower(application.externalLoanId) like :lspLoanIdText");
            parameters.put("lspLoanIdText", "%" + lspLoanIdText.toLowerCase(Locale.ROOT) + "%");
        }
        if (bhawLoanIdText != null) {
            jpql.append(" and lower(account.accountNumber) like :bhawLoanIdText");
            parameters.put("bhawLoanIdText", "%" + bhawLoanIdText.toLowerCase(Locale.ROOT) + "%");
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
