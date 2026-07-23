package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.service.ProductConfigurationService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/product-options")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER','PRODUCT_ADMIN')")
public class ProductOptionsController {

    private final ProductConfigurationService productConfigurationService;

    public ProductOptionsController(ProductConfigurationService productConfigurationService) {
        this.productConfigurationService = productConfigurationService;
    }

    @GetMapping
    public List<ProductOptionResponse> listProductOptions() {
        return productConfigurationService.listProducts().stream()
                .map(ProductOptionsController::toResponse)
                .toList();
    }

    private static ProductOptionResponse toResponse(LoanProduct product) {
        return new ProductOptionResponse(
                product.getId().toString(),
                product.getCode(),
                product.getName(),
                product.getStatus().name()
        );
    }

    public record ProductOptionResponse(
            String id,
            String code,
            String name,
            String status
    ) {
    }
}
