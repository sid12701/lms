package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.service.LspProductCatalogService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lsp/products")
public class LspProductApiController {

    private final LspProductCatalogService lspProductCatalogService;

    public LspProductApiController(LspProductCatalogService lspProductCatalogService) {
        this.lspProductCatalogService = lspProductCatalogService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')")
    public List<LspProductResponse> listProvisionedProducts(Authentication authentication) {
        return lspProductCatalogService.listProvisionedProducts(LspAuthenticationSupport.authenticatedLspId(authentication))
                .stream()
                .map(LspProductApiController::toResponse)
                .toList();
    }

    private static LspProductResponse toResponse(LoanProduct product) {
        return new LspProductResponse(
                product.getId().toString(),
                product.getCode(),
                product.getName(),
                product.getMinPrincipal(),
                product.getMaxPrincipal(),
                product.getInterestRate(),
                product.getProcessingFeeRate(),
                product.getMinTenureMonths(),
                product.getMaxTenureMonths(),
                product.getStatus().name()
        );
    }

    public record LspProductResponse(
            String id,
            String code,
            String name,
            BigDecimal minPrincipal,
            BigDecimal maxPrincipal,
            BigDecimal interestRate,
            BigDecimal processingFeeRate,
            Integer minTenureMonths,
            Integer maxTenureMonths,
            String status
    ) {
    }
}
