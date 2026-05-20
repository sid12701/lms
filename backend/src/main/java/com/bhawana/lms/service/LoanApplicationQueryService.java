package com.bhawana.lms.service;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanApplicationReadRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationQueryService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationQueryService.class);

    private final LoanApplicationReadRepository loanApplicationReadRepository;

    public LoanApplicationQueryService(LoanApplicationReadRepository loanApplicationReadRepository) {
        this.loanApplicationReadRepository = loanApplicationReadRepository;
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplications(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo
    ) {
        return listApplicationsPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                disbursalDateFrom,
                disbursalDateTo,
                null,
                null,
                false
        ).items();
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        validateDateRange(disbursalDateFrom, disbursalDateTo);

        String normalizedQuery = normalizeQuery(query);
        LoanApplicationStatus normalizedStatus = resolveStatus(status);
        if (status != null && normalizedStatus == null) {
            return new PagedResult<>(
                    List.of(),
                    0,
                    PaginationResponseBuilder.resolveOffset(offset, true),
                    PaginationResponseBuilder.resolveLimit(limit, true)
            );
        }

        Instant disbursalFromInstant = disbursalDateFrom == null
                ? null
                : disbursalDateFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant disbursalToInstant = disbursalDateTo == null
                ? null
                : disbursalDateTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        boolean paginationRequested = true;
        int resolvedOffset = PaginationResponseBuilder.resolveOffset(offset, paginationRequested);
        int resolvedLimit = PaginationResponseBuilder.resolveLimit(limit, paginationRequested);

        long startedAt = System.nanoTime();
        PagedResult<LoanApplication> page = loanApplicationReadRepository.findApplications(
                lspId,
                productId,
                normalizedStatus,
                normalizeOptional(sourceChannel),
                normalizedQuery,
                parseApplicationId(normalizedQuery),
                disbursalFromInstant,
                disbursalToInstant,
                paginationRequested,
                resolvedOffset,
                resolvedLimit,
                includePaginationDetails
        );
        log.debug(
                "loan_application_list_query completed lspId={} productId={} status={} sourceChannel={} queryPresent={} disbursalFrom={} disbursalTo={} offset={} limit={} paginationDetails={} resultCount={} durationMs={}",
                lspId,
                productId,
                normalizedStatus,
                normalizeOptional(sourceChannel),
                normalizedQuery != null,
                disbursalDateFrom,
                disbursalDateTo,
                resolvedOffset,
                paginationRequested ? resolvedLimit : null,
                includePaginationDetails,
                page.items().size(),
                elapsedMillis(startedAt)
        );
        return page;
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplicationsForLsp(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query
    ) {
        return listApplicationsPage(lspId, productId, status, sourceChannel, query, null, null, null, null, false)
                .items();
    }

    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsForLspPage(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        return listApplicationsPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                null,
                null,
                offset,
                limit,
                includePaginationDetails
        );
    }

    private static LoanApplicationStatus resolveStatus(String status) {
        String normalizedStatus = normalizeOptional(status);
        if (normalizedStatus == null) {
            return null;
        }
        try {
            return LoanApplicationStatus.valueOf(normalizedStatus.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeQuery(String query) {
        String normalized = normalizeOptional(query);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static UUID parseApplicationId(String normalizedQuery) {
        if (normalizedQuery == null) {
            return null;
        }
        try {
            return UUID.fromString(normalizedQuery);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("disbursalDateFrom cannot be after disbursalDateTo.");
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
