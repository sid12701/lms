package com.bhawana.lms.service;

import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.PagedResult;
import com.bhawana.lms.common.api.PaginationResponseBuilder;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanApplicationReadRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
        // An unknown status used to silently produce an empty page (M14): a caller could not tell
        // "no loans in this status" from "this status does not exist". It is now rejected with the
        // same INVALID_* contract the other validated filters use.
        String normalizedStatusValue = Strings.normalizeOptional(status);
        LoanApplicationStatus normalizedStatus = resolveStatus(normalizedStatusValue);
        if (normalizedStatusValue != null && normalizedStatus == null) {
            throw unknownStatus(normalizedStatusValue);
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
                "loan_application_list_query completed lspId={} productId={} status={} sourceChannelPresent={} queryPresent={} lspLoanIdPresent={} bhawLoanIdPresent={} disbursalFrom={} disbursalTo={} offset={} limit={} paginationDetails={} resultCount={} durationMs={}",
                lspId,
                productId,
                normalizedStatus,
                Strings.normalizeOptional(sourceChannel) != null,
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

    /**
     * H29 — strict ops triage listing: every requested status must name a
     * known lifecycle state (OR semantics across all of them) and the sort
     * field/direction must come from the repository whitelist. Unknown values
     * fail with a clear 422 instead of silently returning one status, no
     * status, or an unsorted page the UI then pretends is sorted.
     */
    @Transactional(readOnly = true)
    public PagedResult<LoanApplication> listApplicationsPageStrict(
            UUID lspId,
            UUID productId,
            List<String> statuses,
            String sourceChannel,
            String query,
            String lspLoanId,
            String bhawLoanId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            String sortBy,
            String sortDir,
            Integer offset,
            Integer limit,
            boolean includePaginationDetails
    ) {
        validateDateRange(disbursalDateFrom, disbursalDateTo);

        Set<LoanApplicationStatus> normalizedStatuses = resolveStatusesStrict(statuses);
        String resolvedSortBy = resolveSortByStrict(sortBy);
        boolean sortDescending = resolveSortDirStrict(sortDir);

        String normalizedQuery = normalizeQuery(query);
        String normalizedLspLoanId = normalizeQuery(lspLoanId);
        String normalizedBhawLoanId = normalizeQuery(bhawLoanId);

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
                normalizedStatuses,
                Strings.normalizeOptional(sourceChannel),
                normalizedQuery,
                parseApplicationId(normalizedQuery),
                normalizedLspLoanId,
                normalizedBhawLoanId,
                disbursalFromInstant,
                disbursalToInstant,
                resolvedSortBy,
                sortDescending,
                paginationRequested,
                resolvedOffset,
                resolvedLimit,
                includePaginationDetails
        );
        log.debug(
                "loan_application_list_query completed lspId={} productId={} statuses={} sourceChannelPresent={} queryPresent={} lspLoanIdPresent={} bhawLoanIdPresent={} disbursalFrom={} disbursalTo={} sortBy={} sortDescending={} offset={} limit={} paginationDetails={} resultCount={} durationMs={}",
                lspId,
                productId,
                normalizedStatuses,
                Strings.normalizeOptional(sourceChannel) != null,
                normalizedQuery != null,
                normalizedLspLoanId != null,
                normalizedBhawLoanId != null,
                disbursalDateFrom,
                disbursalDateTo,
                resolvedSortBy,
                sortDescending,
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

    private static LoanApplicationStatus resolveStatus(String normalizedStatus) {
        if (normalizedStatus == null) {
            return null;
        }
        try {
            return LoanApplicationStatus.valueOf(normalizedStatus.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static BusinessRuleViolationException unknownStatus(String status) {
        String knownStatuses = Arrays.stream(LoanApplicationStatus.values())
                .map(LoanApplicationStatus::name)
                .collect(Collectors.joining(", "));
        return new BusinessRuleViolationException(
                "INVALID_STATUS",
                "Unknown status '" + status + "'. Known statuses: " + knownStatuses + ".",
                Map.of("status", "names an unknown status: " + status)
        );
    }

    private static Set<LoanApplicationStatus> resolveStatusesStrict(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return Set.of();
        }
        Set<LoanApplicationStatus> resolved = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String status : statuses) {
            String normalized = Strings.normalizeOptional(status);
            if (normalized == null) {
                continue;
            }
            try {
                resolved.add(LoanApplicationStatus.valueOf(normalized.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                unknown.add(status);
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "INVALID_STATUS",
                    "Unknown loan application status: " + String.join(", ", unknown) + ".",
                    Map.of("status", "must be a known loan application status")
            );
        }
        return resolved;
    }

    private static String resolveSortByStrict(String sortBy) {
        String normalized = Strings.normalizeOptional(sortBy);
        if (normalized == null) {
            return "createdAt";
        }
        if (!LoanApplicationReadRepository.ALLOWED_SORT_FIELDS.contains(normalized)) {
            throw new BusinessRuleViolationException(
                    "INVALID_SORT",
                    "Unknown sort field: " + normalized + ". Allowed: createdAt, updatedAt, requestedAmount, status.",
                    Map.of("sortBy", "must be one of createdAt, updatedAt, requestedAmount, status")
            );
        }
        return normalized;
    }

    private static boolean resolveSortDirStrict(String sortDir) {
        String normalized = Strings.normalizeOptional(sortDir);
        if (normalized == null) {
            // Preserve the historical default view: newest first.
            return true;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if ("ASC".equals(upper)) {
            return false;
        }
        if ("DESC".equals(upper)) {
            return true;
        }
        throw new BusinessRuleViolationException(
                "INVALID_SORT",
                "Unknown sort direction: " + normalized + ". Allowed: asc, desc.",
                Map.of("sortDir", "must be either asc or desc")
        );
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
