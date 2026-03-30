package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LspRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductConfigurationService {

    private final LoanProductRepository loanProductRepository;
    private final LspRepository lspRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;

    public ProductConfigurationService(
            LoanProductRepository loanProductRepository,
            LspRepository lspRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository
    ) {
        this.loanProductRepository = loanProductRepository;
        this.lspRepository = lspRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
    }

    @Transactional(readOnly = true)
    public List<LoanProduct> listProducts() {
        return loanProductRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(LoanProduct::getCode))
                .toList();
    }

    @Transactional(readOnly = true)
    public LoanProduct getProduct(UUID productId) {
        return loanProductRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan product id: " + productId));
    }

    @Transactional
    public LoanProduct createProduct(
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
        String normalizedCode = normalizeCode(code);
        validateRanges(minPrincipal, maxPrincipal, interestRate, processingFeeRate, minTenureMonths, maxTenureMonths);

        if (loanProductRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new IllegalArgumentException("Loan product code already exists: " + normalizedCode);
        }

        LoanProduct product = new LoanProduct(
                normalizedCode,
                name.trim(),
                scaleCurrency(minPrincipal),
                scaleCurrency(maxPrincipal),
                scaleRate(interestRate),
                scaleRate(processingFeeRate),
                minTenureMonths,
                maxTenureMonths,
                status
        );
        return loanProductRepository.save(product);
    }

    @Transactional
    public LoanProduct updateProduct(
            UUID productId,
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
        LoanProduct product = loanProductRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan product id: " + productId));

        String normalizedCode = normalizeCode(code);
        validateRanges(minPrincipal, maxPrincipal, interestRate, processingFeeRate, minTenureMonths, maxTenureMonths);

        loanProductRepository.findByCodeIgnoreCase(normalizedCode)
                .filter(existing -> !existing.getId().equals(productId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Loan product code already exists: " + normalizedCode);
                });

        product.update(
                normalizedCode,
                name.trim(),
                scaleCurrency(minPrincipal),
                scaleCurrency(maxPrincipal),
                scaleRate(interestRate),
                scaleRate(processingFeeRate),
                minTenureMonths,
                maxTenureMonths,
                status
        );
        return loanProductRepository.save(product);
    }

    @Transactional(readOnly = true)
    public List<Lsp> listProductMappings(UUID productId) {
        getProduct(productId);
        return loanProductLspMappingRepository.findAllByLoanProduct_Id(productId).stream()
                .map(LoanProductLspMapping::getLsp)
                .sorted(java.util.Comparator.comparing(Lsp::getCode))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductLspMappingView> listAllProductMappings() {
        return loanProductLspMappingRepository.findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        mapping -> mapping.getLoanProduct().getId(),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.mapping(mapping -> mapping.getLsp().getId(), java.util.stream.Collectors.toCollection(java.util.TreeSet::new))
                ))
                .entrySet().stream()
                .map(entry -> new ProductLspMappingView(
                        entry.getKey(),
                        List.copyOf(entry.getValue())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LoanProductLspMapping> listMappings() {
        return loanProductLspMappingRepository.findAll().stream()
                .sorted(java.util.Comparator
                        .comparing((LoanProductLspMapping mapping) -> mapping.getLsp().getCode())
                        .thenComparing(mapping -> mapping.getLoanProduct().getCode()))
                .toList();
    }

    @Transactional
    public LoanProductLspMapping upsertMapping(UUID lspId, UUID productId, boolean enabled) {
        LoanProduct product = getProduct(productId);
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        return loanProductLspMappingRepository.findByLsp_IdAndLoanProduct_Id(lspId, productId)
                .map(existing -> {
                    existing.update(enabled);
                    return loanProductLspMappingRepository.save(existing);
                })
                .orElseGet(() -> loanProductLspMappingRepository.save(new LoanProductLspMapping(product, lsp, enabled)));
    }

    @Transactional
    public List<Lsp> replaceProductMappings(UUID productId, Set<UUID> lspIds) {
        LoanProduct product = getProduct(productId);
        Set<UUID> distinctLspIds = Set.copyOf(lspIds);
        List<Lsp> lsps = lspRepository.findAllById(distinctLspIds).stream()
                .sorted(java.util.Comparator.comparing(Lsp::getCode))
                .toList();

        if (lsps.size() != distinctLspIds.size()) {
            throw new IllegalArgumentException("One or more requested LSP ids are not available.");
        }

        loanProductLspMappingRepository.deleteByLoanProduct_Id(productId);
        List<LoanProductLspMapping> mappings = lsps.stream()
                .map(lsp -> new LoanProductLspMapping(product, lsp, true))
                .toList();
        loanProductLspMappingRepository.saveAll(mappings);
        return lsps;
    }

    public record ProductLspMappingView(UUID productId, List<UUID> lspIds) {
    }

    private static void validateRanges(
            BigDecimal minPrincipal,
            BigDecimal maxPrincipal,
            BigDecimal interestRate,
            BigDecimal processingFeeRate,
            int minTenureMonths,
            int maxTenureMonths
    ) {
        if (minPrincipal.compareTo(BigDecimal.ZERO) <= 0 || maxPrincipal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Principal amounts must be greater than zero.");
        }
        if (minPrincipal.compareTo(maxPrincipal) > 0) {
            throw new IllegalArgumentException("Minimum principal cannot exceed maximum principal.");
        }
        if (interestRate.compareTo(BigDecimal.ZERO) < 0 || interestRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Interest rate must be between 0 and 100.");
        }
        if (processingFeeRate.compareTo(BigDecimal.ZERO) < 0 || processingFeeRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Processing fee rate must be between 0 and 100.");
        }
        if (minTenureMonths <= 0 || maxTenureMonths <= 0) {
            throw new IllegalArgumentException("Tenure must be greater than zero.");
        }
        if (minTenureMonths > maxTenureMonths) {
            throw new IllegalArgumentException("Minimum tenure cannot exceed maximum tenure.");
        }
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase();
    }

    private static BigDecimal scaleCurrency(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleRate(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
