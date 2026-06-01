package com.bhawana.lms.web;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.service.BorrowerBankDetailsService;
import com.bhawana.lms.service.BorrowerBankDetailsService.BorrowerBankDetailsCommand;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lsp/borrowers")
public class LspBorrowerApiController {

    private final BorrowerBankDetailsService borrowerBankDetailsService;

    public LspBorrowerApiController(BorrowerBankDetailsService borrowerBankDetailsService) {
        this.borrowerBankDetailsService = borrowerBankDetailsService;
    }

    @GetMapping("/{borrowerId}/bank-details")
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public BorrowerBankDetailsResponse getBankDetails(
            Authentication authentication,
            @PathVariable UUID borrowerId
    ) {
        Borrower borrower = borrowerBankDetailsService.getBorrowerForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                borrowerId
        );
        return BorrowerBankDetailsResponse.from(borrower);
    }

    @PatchMapping("/{borrowerId}/bank-details")
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public BorrowerBankDetailsResponse updateBankDetails(
            Authentication authentication,
            HttpServletRequest httpServletRequest,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody BorrowerBankDetailsRequest request
    ) {
        Borrower borrower = borrowerBankDetailsService.updateBankDetailsForLsp(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                borrowerId,
                request.toCommand(),
                authentication.getName(),
                httpServletRequest.getRemoteAddr()
        );
        return BorrowerBankDetailsResponse.from(borrower);
    }

    public record BorrowerBankDetailsRequest(
            @NotBlank @Size(max = 64) String bankAccountNumber,
            @Size(max = 255) String bankName,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{4}0[A-Za-z0-9]{6}$", message = "IFSC code must be valid")
            String ifscCode,
            @Size(max = 255) String accountHolderName
    ) {
        BorrowerBankDetailsCommand toCommand() {
            return new BorrowerBankDetailsCommand(
                    bankAccountNumber,
                    bankName,
                    ifscCode,
                    accountHolderName
            );
        }
    }

    public record BorrowerBankDetailsResponse(
            String borrowerId,
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName
    ) {
        static BorrowerBankDetailsResponse from(Borrower borrower) {
            return new BorrowerBankDetailsResponse(
                    borrower.getId().toString(),
                    borrower.getBankAccountNumber(),
                    borrower.getBankName(),
                    borrower.getIfscCode(),
                    borrower.getAccountHolderName()
            );
        }
    }
}
