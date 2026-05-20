package com.bhawana.lms.web;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.service.AdminDirectoryService;
import com.bhawana.lms.service.AdminDirectoryService.BorrowerDetailView;
import com.bhawana.lms.service.AdminDirectoryService.BorrowerLoanView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/borrowers")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
public class BorrowerAdminController {

    private final AdminDirectoryService adminDirectoryService;

    public BorrowerAdminController(AdminDirectoryService adminDirectoryService) {
        this.adminDirectoryService = adminDirectoryService;
    }

    @GetMapping("/{borrowerId}")
    public BorrowerDetailResponse getBorrowerDetail(@PathVariable UUID borrowerId) {
        return toResponse(adminDirectoryService.getBorrowerDetail(borrowerId));
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
                maskBankAccount(borrower.getBankAccountNumber()),
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
                        .toList()
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

    private static String maskBankAccount(String account) {
        if (account == null || account.length() < 4) {
            return null;
        }
        return "XXXX" + account.substring(account.length() - 4);
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
            List<BorrowerLoanResponse> loans
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
