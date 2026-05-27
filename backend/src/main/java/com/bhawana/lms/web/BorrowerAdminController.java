package com.bhawana.lms.web;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.service.AdminDirectoryService;
import com.bhawana.lms.service.AdminDirectoryService.BorrowerDelinquencyAggregate;
import com.bhawana.lms.service.AdminDirectoryService.BorrowerDetailView;
import com.bhawana.lms.service.AdminDirectoryService.BorrowerLoanView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/borrowers")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
public class BorrowerAdminController {

    private final AdminDirectoryService adminDirectoryService;

    public BorrowerAdminController(AdminDirectoryService adminDirectoryService) {
        this.adminDirectoryService = adminDirectoryService;
    }

    @GetMapping
    public ResponseEntity<List<BorrowerSummaryResponse>> listBorrowers(
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) @Min(0) Integer offset,
            @RequestParam(required = false) @Min(1) @Max(1000) Integer limit,
            @RequestParam(required = false) String paginationDetails
    ) {
        boolean includePaginationDetails = PaginationResponseBuilder.includePaginationDetails(paginationDetails);
        PagedResult<Borrower> page = adminDirectoryService.listBorrowers(
                query, offset, limit, includePaginationDetails);
        PagedResult<BorrowerSummaryResponse> mapped = new PagedResult<>(
                page.items().stream().map(BorrowerAdminController::toSummaryResponse).toList(),
                page.totalCount(),
                page.offset(),
                page.limit()
        );
        return PaginationResponseBuilder.toListResponse(mapped, includePaginationDetails);
    }

    @GetMapping("/{borrowerId}")
    public BorrowerDetailResponse getBorrowerDetail(@PathVariable UUID borrowerId) {
        return toResponse(adminDirectoryService.getBorrowerDetail(borrowerId));
    }

    private static BorrowerSummaryResponse toSummaryResponse(Borrower borrower) {
        return new BorrowerSummaryResponse(
                borrower.getId().toString(),
                borrower.getFullName(),
                borrower.getPan(),
                borrower.getMobile(),
                borrower.getEmail(),
                borrower.getCity(),
                borrower.getState(),
                maskAadhar(borrower.getAadharNumber()),
                borrower.getVisibleLspIds().stream()
                        .map(UUID::toString)
                        .collect(Collectors.toUnmodifiableSet())
        );
    }

    private static BorrowerDetailResponse toResponse(BorrowerDetailView view) {
        Borrower borrower = view.borrower();
        return new BorrowerDetailResponse(
                borrower.getId().toString(),
                borrower.getFullName(),
                borrower.getPan(),
                borrower.getMobile(),
                borrower.getEmail(),
                borrower.getDateOfBirth(),
                borrower.getGender(),
                borrower.getMaritalStatus(),
                borrower.getFatherName(),
                maskAadhar(borrower.getAadharNumber()),
                borrower.getAddressLine1(),
                borrower.getAddressLine2(),
                borrower.getCity(),
                borrower.getState(),
                borrower.getAddressZipCode(),
                borrower.getSpouseName(),
                borrower.getEmploymentType(),
                borrower.getOrganizationName(),
                borrower.getEmployeeId(),
                borrower.getEmploymentCity(),
                borrower.getEmploymentState(),
                borrower.getEmploymentZip(),
                borrower.getMonthlyIncome(),
                borrower.getAnnualIncome(),
                borrower.getBankAccountNumber(),
                borrower.getBankName(),
                borrower.getIfscCode(),
                borrower.getAccountHolderName(),
                borrower.getReferencePersonName(),
                borrower.getReferencePersonNumber(),
                borrower.getVisibleLspIds().stream()
                        .map(UUID::toString)
                        .collect(Collectors.toUnmodifiableSet()),
                view.loans().stream()
                        .map(BorrowerAdminController::toLoanResponse)
                        .toList(),
                toDelinquencyResponse(view.delinquency())
        );
    }

    private static BorrowerDelinquencyResponse toDelinquencyResponse(BorrowerDelinquencyAggregate aggregate) {
        if (aggregate == null) {
            return new BorrowerDelinquencyResponse(BigDecimal.ZERO.setScale(2), 0, 0, null);
        }
        return new BorrowerDelinquencyResponse(
                aggregate.activeOverdueAmount(),
                aggregate.maxDaysPastDue(),
                aggregate.overdueLoanCount(),
                aggregate.overdueLoanCount() > 0 && aggregate.bucket() != null
                        ? aggregate.bucket().name()
                        : null
        );
    }

    private static BorrowerLoanResponse toLoanResponse(BorrowerLoanView loan) {
        return new BorrowerLoanResponse(
                loan.loanAccountId() == null ? null : loan.loanAccountId().toString(),
                loan.applicationId() == null ? null : loan.applicationId().toString(),
                loan.accountNumber(),
                loan.lspId() == null ? null : loan.lspId().toString(),
                loan.lspCode(),
                loan.lspName(),
                loan.loanProductCode(),
                loan.status() == null ? null : loan.status().name(),
                loan.principalAmount(),
                loan.tenureMonths(),
                loan.approvedAt(),
                loan.disbursedAt(),
                loan.closureReason(),
                loan.closedAt(),
                loan.closedByUsername(),
                loan.createdAt()
        );
    }

    private static String maskAadhar(String aadhar) {
        if (aadhar == null || aadhar.length() < 4) {
            return null;
        }
        return "XXXXXXXX" + aadhar.substring(aadhar.length() - 4);
    }

    public record BorrowerDetailResponse(
            String id,
            String fullName,
            String pan,
            String mobile,
            String email,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus,
            String fatherName,
            String aadharNumberMasked,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String addressZipCode,
            String spouseName,
            String employmentType,
            String organizationName,
            String employeeId,
            String employmentCity,
            String employmentState,
            String employmentZip,
            BigDecimal monthlyIncome,
            BigDecimal annualIncome,
            String bankAccountNumberMasked,
            String bankName,
            String ifscCode,
            String accountHolderName,
            String referencePersonName,
            String referencePersonNumber,
            Set<String> visibleLspIds,
            List<BorrowerLoanResponse> loans,
            BorrowerDelinquencyResponse delinquency
    ) {
    }

    /**
     * Gap #6: aggregate delinquency block on the borrower-admin response.
     * `bucket` is null when the borrower has no overdue installments
     * (collapses the "CURRENT" sentinel — keeps the response shape honest
     * about whether the borrower is in trouble).
     */
    public record BorrowerDelinquencyResponse(
            BigDecimal activeOverdueAmount,
            int maxDaysPastDue,
            int overdueLoanCount,
            String bucket
    ) {
    }

    /** Lightweight projection for the borrowers directory list page. */
    public record BorrowerSummaryResponse(
            String id,
            String fullName,
            String pan,
            String mobile,
            String email,
            String city,
            String state,
            String aadharNumberMasked,
            Set<String> visibleLspIds
    ) {
    }

    public record BorrowerLoanResponse(
            String loanAccountId,
            String applicationId,
            String accountNumber,
            String lspId,
            String lspCode,
            String lspName,
            String loanProductCode,
            String status,
            BigDecimal principalAmount,
            int tenureMonths,
            Instant approvedAt,
            Instant disbursedAt,
            String closureReason,
            Instant closedAt,
            String closedByUsername,
            Instant createdAt
    ) {
    }
}
