package com.bhawana.lms.web;

import com.bhawana.lms.service.ProductConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/internal/admin/product-lsp-mappings")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')")
public class ProductLspMappingAdminController {

    private final ProductConfigurationService productConfigurationService;

    public ProductLspMappingAdminController(ProductConfigurationService productConfigurationService) {
        this.productConfigurationService = productConfigurationService;
    }

    @GetMapping
    public List<ProductLspMappingResponse> listMappings() {
        return productConfigurationService.listAllProductMappings().stream()
                .map(mapping -> new ProductLspMappingResponse(
                        mapping.productId().toString(),
                        mapping.lspIds().stream().map(UUID::toString).toList()
                ))
                .toList();
    }

    @GetMapping("/entries")
    public List<ProductLspMappingEntryResponse> listEntries() {
        return productConfigurationService.listMappings().stream()
                .map(mapping -> new ProductLspMappingEntryResponse(
                        mapping.getId().toString(),
                        mapping.getLsp().getId().toString(),
                        mapping.getLsp().getCode(),
                        mapping.getLsp().getName(),
                        mapping.getLoanProduct().getId().toString(),
                        mapping.getLoanProduct().getCode(),
                        mapping.getLoanProduct().getName(),
                        mapping.isEnabled()
                ))
                .toList();
    }

    @PutMapping("/{productId}")
    public ProductLspMappingResponse replaceMappings(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductLspMappingRequest request
    ) {
        productConfigurationService.replaceProductMappings(productId, request.lspIds());
        return new ProductLspMappingResponse(
                productId.toString(),
                request.lspIds().stream().sorted().map(UUID::toString).toList()
        );
    }

    @PostMapping("/entries")
    public ProductLspMappingEntryResponse upsertEntry(@Valid @RequestBody UpsertProductLspMappingRequest request) {
        var mapping = productConfigurationService.upsertMapping(request.lspId(), request.productId(), request.enabled());
        return new ProductLspMappingEntryResponse(
                mapping.getId().toString(),
                mapping.getLsp().getId().toString(),
                mapping.getLsp().getCode(),
                mapping.getLsp().getName(),
                mapping.getLoanProduct().getId().toString(),
                mapping.getLoanProduct().getCode(),
                mapping.getLoanProduct().getName(),
                mapping.isEnabled()
        );
    }

    public record ProductLspMappingRequest(
            @NotNull Set<UUID> lspIds
    ) {
    }

    public record ProductLspMappingResponse(
            String productId,
            List<String> lspIds
    ) {
    }

    public record UpsertProductLspMappingRequest(
            @NotNull UUID lspId,
            @NotNull UUID productId,
            boolean enabled
    ) {
    }

    public record ProductLspMappingEntryResponse(
            String id,
            String lspId,
            String lspCode,
            String lspName,
            String productId,
            String productCode,
            String productName,
            boolean enabled
    ) {
    }
}
