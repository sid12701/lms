package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductAuditEvent;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.service.ProductConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/products")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')")
public class LoanProductAdminController {

    private final ProductConfigurationService productConfigurationService;

    public LoanProductAdminController(ProductConfigurationService productConfigurationService) {
        this.productConfigurationService = productConfigurationService;
    }

    @GetMapping
    public List<ProductResponse> listProducts() {
        return productConfigurationService.listProducts().stream()
                .map(LoanProductAdminController::toResponse)
                .toList();
    }

    @GetMapping("/{productId}")
    public ProductResponse getProduct(@PathVariable UUID productId) {
        return toResponse(productConfigurationService.getProduct(productId));
    }

    @PostMapping
    public ProductResponse createProduct(@Valid @RequestBody ProductRequest request) {
        LoanProduct product = productConfigurationService.createProduct(
                request.code(),
                request.name(),
                request.minPrincipal(),
                request.maxPrincipal(),
                request.interestRate(),
                request.processingFeeRate(),
                request.minTenureMonths(),
                request.maxTenureMonths(),
                request.status()
        );
        return toResponse(product);
    }

    @PutMapping("/{productId}")
    public ProductResponse updateProduct(@PathVariable UUID productId, @Valid @RequestBody ProductRequest request) {
        LoanProduct product = productConfigurationService.updateProduct(
                productId,
                request.code(),
                request.name(),
                request.minPrincipal(),
                request.maxPrincipal(),
                request.interestRate(),
                request.processingFeeRate(),
                request.minTenureMonths(),
                request.maxTenureMonths(),
                request.status()
        );
        return toResponse(product);
    }

    @GetMapping("/{productId}/mappings")
    public ProductMappingResponse getProductMappings(@PathVariable UUID productId) {
        LoanProduct product = productConfigurationService.getProduct(productId);
        List<LspMappingItem> mappedLsps = productConfigurationService.listProductMappings(productId).stream()
                .map(LoanProductAdminController::toLspMappingItem)
                .toList();
        return new ProductMappingResponse(product.getId().toString(), product.getCode(), product.getName(), mappedLsps);
    }

    @PutMapping("/{productId}/mappings")
    public ProductMappingResponse replaceProductMappings(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductMappingRequest request
    ) {
        LoanProduct product = productConfigurationService.getProduct(productId);
        List<LspMappingItem> mappedLsps = productConfigurationService.replaceProductMappings(productId, request.lspIds()).stream()
                .map(LoanProductAdminController::toLspMappingItem)
                .toList();
        return new ProductMappingResponse(product.getId().toString(), product.getCode(), product.getName(), mappedLsps);
    }

    @GetMapping("/{productId}/audit-events")
    public List<ProductAuditEventResponse> listAuditEvents(@PathVariable UUID productId) {
        return productConfigurationService.listAuditEvents(productId).stream()
                .map(LoanProductAdminController::toAuditResponse)
                .toList();
    }

    private static ProductResponse toResponse(LoanProduct product) {
        return new ProductResponse(
                product.getId().toString(),
                product.getCode(),
                product.getName(),
                product.getMinPrincipal(),
                product.getMaxPrincipal(),
                product.getInterestRate(),
                product.getProcessingFeeRate(),
                product.getMinTenureMonths(),
                product.getMaxTenureMonths(),
                product.getStatus().name(),
                product.getCreatedAt()
        );
    }

    private static LspMappingItem toLspMappingItem(Lsp lsp) {
        return new LspMappingItem(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name()
        );
    }

    private static ProductAuditEventResponse toAuditResponse(LoanProductAuditEvent event) {
        return new ProductAuditEventResponse(
                event.getId().toString(),
                event.getLoanProduct().getId().toString(),
                event.getAction().name(),
                event.getActorUsername(),
                event.getSummary(),
                event.getCorrelationId(),
                event.getCreatedAt()
        );
    }

    public record ProductRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull @DecimalMin(value = "0.01") BigDecimal minPrincipal,
            @NotNull @DecimalMin(value = "0.01") BigDecimal maxPrincipal,
            @NotNull @DecimalMin(value = "0.00") @DecimalMax(value = "100.00") BigDecimal interestRate,
            @NotNull @DecimalMin(value = "0.00") @DecimalMax(value = "100.00") BigDecimal processingFeeRate,
            @NotNull @Min(1) Integer minTenureMonths,
            @NotNull @Min(1) Integer maxTenureMonths,
            LoanProductStatus status
    ) {
        public ProductRequest {
            if (status == null) {
                status = LoanProductStatus.ACTIVE;
            }
        }
    }

    public record ProductResponse(
            String id,
            String code,
            String name,
            BigDecimal minPrincipal,
            BigDecimal maxPrincipal,
            BigDecimal interestRate,
            BigDecimal processingFeeRate,
            Integer minTenureMonths,
            Integer maxTenureMonths,
            String status,
            Instant createdAt
    ) {
    }

    public record ProductMappingRequest(
            @NotNull Set<UUID> lspIds
    ) {
    }

    public record ProductMappingResponse(
            String productId,
            String productCode,
            String productName,
            List<LspMappingItem> mappedLsps
    ) {
    }

    public record LspMappingItem(
            String id,
            String code,
            String name,
            String status
    ) {
    }

    public record ProductAuditEventResponse(
            String id,
            String productId,
            String action,
            String actorUsername,
            String summary,
            String correlationId,
            Instant createdAt
    ) {
    }
}
