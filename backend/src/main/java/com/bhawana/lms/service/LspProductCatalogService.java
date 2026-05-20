package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LspProductCatalogService {

    private final LoanProductLspMappingRepository loanProductLspMappingRepository;

    public LspProductCatalogService(LoanProductLspMappingRepository loanProductLspMappingRepository) {
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
    }

    @Transactional(readOnly = true)
    public List<LoanProduct> listProvisionedProducts(UUID lspId) {
        return loanProductLspMappingRepository.findAllByLsp_IdAndEnabledTrue(lspId).stream()
                .filter(mapping -> mapping.getLsp().getStatus() == LspStatus.ACTIVE)
                .map(mapping -> mapping.getLoanProduct())
                .filter(product -> product.getStatus() == LoanProductStatus.ACTIVE)
                .sorted(Comparator.comparing(LoanProduct::getCode))
                .toList();
    }
}
