package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LspRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.seed.sample-data", name = "enabled", havingValue = "true")
public class SampleCatalogSeedService implements ApplicationRunner {

    private static final List<SampleLsp> SAMPLE_LSPS = List.of(
            new SampleLsp("APEX", "Apex Finance", LspStatus.ACTIVE),
            new SampleLsp("NORTH", "Northbridge Capital", LspStatus.ACTIVE),
            new SampleLsp("TRUEFIN", "TrueFin Partners", LspStatus.ACTIVE)
    );

    private static final List<SampleProduct> SAMPLE_PRODUCTS = List.of(
            new SampleProduct(
                    "SALARY-PLUS",
                    "Salary Plus",
                    new BigDecimal("5000.00"),
                    new BigDecimal("250000.00"),
                    new BigDecimal("18.50"),
                    new BigDecimal("2.25"),
                    6,
                    24,
                    LoanProductStatus.ACTIVE
            ),
            new SampleProduct(
                    "MERCHANT-FLEX",
                    "Merchant Flex",
                    new BigDecimal("25000.00"),
                    new BigDecimal("500000.00"),
                    new BigDecimal("21.00"),
                    new BigDecimal("2.75"),
                    6,
                    18,
                    LoanProductStatus.ACTIVE
            ),
            new SampleProduct(
                    "EDU-ACCESS",
                    "Education Access",
                    new BigDecimal("15000.00"),
                    new BigDecimal("300000.00"),
                    new BigDecimal("14.25"),
                    new BigDecimal("1.25"),
                    12,
                    36,
                    LoanProductStatus.DRAFT
            )
    );

    private static final Map<String, List<String>> SAMPLE_MAPPINGS = Map.of(
            "SALARY-PLUS", List.of("APEX", "NORTH"),
            "MERCHANT-FLEX", List.of("NORTH", "TRUEFIN"),
            "EDU-ACCESS", List.of("APEX")
    );

    private final LspRepository lspRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final ProductConfigurationService productConfigurationService;

    public SampleCatalogSeedService(
            LspRepository lspRepository,
            LoanProductRepository loanProductRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            ProductConfigurationService productConfigurationService
    ) {
        this.lspRepository = lspRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.productConfigurationService = productConfigurationService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, Lsp> lspByCode = seedLsps();
        Map<String, LoanProduct> productByCode = seedProducts();
        seedMappings(productByCode, lspByCode);
    }

    private Map<String, Lsp> seedLsps() {
        Map<String, Lsp> lspByCode = new LinkedHashMap<>();
        for (SampleLsp sampleLsp : SAMPLE_LSPS) {
            Lsp lsp = lspRepository.findByCodeIgnoreCase(sampleLsp.code())
                    .orElseGet(() -> lspRepository.save(new Lsp(
                            sampleLsp.code(),
                            sampleLsp.name(),
                            sampleLsp.status()
                    )));
            lspByCode.put(sampleLsp.code(), lsp);
        }
        return lspByCode;
    }

    private Map<String, LoanProduct> seedProducts() {
        Map<String, LoanProduct> productByCode = new LinkedHashMap<>();
        for (SampleProduct sampleProduct : SAMPLE_PRODUCTS) {
            LoanProduct product = loanProductRepository.findByCodeIgnoreCase(sampleProduct.code())
                    .orElseGet(() -> productConfigurationService.createProduct(
                            sampleProduct.code(),
                            sampleProduct.name(),
                            sampleProduct.minPrincipal(),
                            sampleProduct.maxPrincipal(),
                            sampleProduct.interestRate(),
                            sampleProduct.processingFeeRate(),
                            sampleProduct.minTenureMonths(),
                            sampleProduct.maxTenureMonths(),
                            sampleProduct.status()
                    ));
            productByCode.put(sampleProduct.code(), product);
        }
        return productByCode;
    }

    private void seedMappings(Map<String, LoanProduct> productByCode, Map<String, Lsp> lspByCode) {
        for (Map.Entry<String, List<String>> entry : SAMPLE_MAPPINGS.entrySet()) {
            LoanProduct product = productByCode.get(entry.getKey());
            if (product == null) {
                continue;
            }

            if (!loanProductLspMappingRepository.findAllByLoanProduct_Id(product.getId()).isEmpty()) {
                continue;
            }

            Set<java.util.UUID> lspIds = entry.getValue().stream()
                    .map(lspByCode::get)
                    .filter(java.util.Objects::nonNull)
                    .map(Lsp::getId)
                    .collect(java.util.stream.Collectors.toSet());

            if (!lspIds.isEmpty()) {
                productConfigurationService.replaceProductMappings(product.getId(), lspIds);
            }
        }
    }

    private record SampleLsp(String code, String name, LspStatus status) {
    }

    private record SampleProduct(
            String code,
            String name,
            BigDecimal minPrincipal,
            BigDecimal maxPrincipal,
            BigDecimal interestRate,
            BigDecimal processingFeeRate,
            int minTenureMonths,
            int maxTenureMonths,
            LoanProductStatus status
    ) {
    }
}
