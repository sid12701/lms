package com.bhawana.lms.service;

import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import com.bhawana.lms.common.web.ResourceNotFoundException;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanApplicationReadRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationQueryService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationQueryService.class);

    private final LoanApplicationReadRepository loanApplicationReadRepository;
    private final LoanApplicationRepository loanApplicationRepository;

    public LoanApplicationQueryService(
            LoanApplicationReadRepository loanApplicationReadRepository,
            LoanApplicationRepository loanApplicationRepository
    ) {
        this.loanApplicationReadRepository = loanApplicationReadRepository;
        this.loanApplicationRepository = loanApplicationRepository;
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplicationForLsp(UUID lspId, UUID applicationId) {
        LoanApplication application = loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
        if (!application.getLsp().getId().equals(lspId)) {
            throw new ResourceNotFoundException("Unknown loan application id: " + applicationId);
        }
        return application;
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplicationForLspByExternalLoanId(UUID lspId, String externalLoanId) {
        String normalizedExternalLoanId = Strings.normalizeOptional(externalLoanId);
        if (normalizedExternalLoanId == null) {
            throw new IllegalArgumentException("externalLoanId is required.");
        }
        return loanApplicationRepository.findDetailedByLsp_IdAndExternalLoanIdIgnoreCase(lspId, normalizedExternalLoanId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Unknown external loan id for the authenticated LSP: " + normalizedExternalLoanId
                ));
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
                null,
                null,
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
            String lspLoanId,
            String bhawLoanId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        validateDateRange(disbursalDateFrom, disbursalDateTo);

        String normalizedQuery = normalizeQuery(query);
        String normalizedLspLoanId = normalizeQuery(lspLoanId);
        String normalizedBhawLoanId = normalizeQuery(bhawLoanId);
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
                Strings.normalizeOptional(sourceChannel),
                normalizedQuery,
                parseApplicationId(normalizedQuery),
                normalizedLspLoanId,
                normalizedBhawLoanId,
                disbursalFromInstant,
                disbursalToInstant,
                paginationRequested,
                resolvedOffset,
                resolvedLimit,
                includePaginationDetails
        );
        log.debug(
                "loan_application_list_query completed lspId={} productId={} status={} sourceChannel={} queryPresent={} lspLoanIdPresent={} bhawLoanIdPresent={} disbursalFrom={} disbursalTo={} offset={} limit={} paginationDetails={} resultCount={} durationMs={}",
                lspId,
                productId,
                normalizedStatus,
                Strings.normalizeOptional(sourceChannel),
                normalizedQuery != null,
                normalizedLspLoanId != null,
                normalizedBhawLoanId != null,
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
        return listApplicationsPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                null,
                null,
                disbursalDateFrom,
                disbursalDateTo,
                offset,
                limit,
                includePaginationDetails
        );
    }

    private static LoanApplicationStatus resolveStatus(String status) {
        String normalizedStatus = Strings.normalizeOptional(status);
        if (normalizedStatus == null) {
            return null;
        }
        try {
            return LoanApplicationStatus.valueOf(normalizedStatus.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String normalizeQuery(String query) {
        String normalized = Strings.normalizeOptional(query);
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
            throw new BusinessRuleViolationException(
                    "INVALID_DISBURSAL_DATE_RANGE",
                    "disbursalDateFrom cannot be after disbursalDateTo.",
                    Map.of("disbursalDateFrom", "cannot be after disbursalDateTo")
            );
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
