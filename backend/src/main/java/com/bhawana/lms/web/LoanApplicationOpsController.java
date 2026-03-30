package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.service.LoanApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ops/loan-applications")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
public class LoanApplicationOpsController {

    private final LoanApplicationService loanApplicationService;

    public LoanApplicationOpsController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @GetMapping
    public List<LoanApplicationResponse> listApplications(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false, name = "q") String query
    ) {
        return loanApplicationService.listApplications(lspId, productId, query).stream()
                .map(LoanApplicationOpsController::toResponse)
                .toList();
    }

    @PostMapping
    public LoanApplicationResponse createApplication(@Valid @RequestBody LoanApplicationRequest request) {
        LoanApplication application = loanApplicationService.createApplication(
                request.lspId(),
                request.productId(),
                request.externalLoanId(),
                request.sourceChannel(),
                request.borrowerPan(),
                request.borrowerFullName(),
                request.borrowerMobile(),
                request.borrowerEmail(),
                request.requestedAmount(),
                request.tenureMonths()
        );
        return toResponse(application);
    }

    private static LoanApplicationResponse toResponse(LoanApplication application) {
        return new LoanApplicationResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getPan(),
                application.getBorrower().getMobile(),
                application.getBorrower().getEmail(),
                application.getLsp().getId().toString(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getExternalLoanId(),
                application.getSourceChannel(),
                application.getRequestedAmount(),
                application.getRequestedTenureMonths(),
                application.getStatus().name(),
                application.getCreatedAt().toString()
        );
    }

    public record LoanApplicationRequest(
            @NotNull UUID lspId,
            @NotNull UUID productId,
            @NotBlank String externalLoanId,
            @NotBlank String sourceChannel,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$", message = "PAN must be a valid 10-character PAN") String borrowerPan,
            @NotBlank String borrowerFullName,
            @NotBlank @Pattern(regexp = "^[0-9]{10,15}$", message = "Mobile must contain 10 to 15 digits") String borrowerMobile,
            @Email String borrowerEmail,
            @NotNull @DecimalMin("0.01") BigDecimal requestedAmount,
            @NotNull @Min(1) Integer tenureMonths
    ) {
    }

    public record LoanApplicationResponse(
            String id,
            String borrowerId,
            String borrowerFullName,
            String borrowerPan,
            String borrowerMobile,
            String borrowerEmail,
            String lspId,
            String lspCode,
            String lspName,
            String productId,
            String productCode,
            String productName,
            String externalLoanId,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            String status,
            String createdAt
    ) {
    }
}
