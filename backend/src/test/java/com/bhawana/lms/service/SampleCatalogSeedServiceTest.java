package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.repo.LoanProductAuditEventRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LspRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.seed.sample-data.enabled=true")
@ActiveProfiles("test")
class SampleCatalogSeedServiceTest {

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private LoanProductAuditEventRepository loanProductAuditEventRepository;

    @Test
    void seedsSampleCatalogForNonProductionEnvironments() {
        assertThat(lspRepository.findByCodeIgnoreCase("APEX")).isPresent();
        assertThat(lspRepository.findByCodeIgnoreCase("NORTH")).isPresent();
        assertThat(lspRepository.findByCodeIgnoreCase("TRUEFIN")).isPresent();

        var salaryPlus = loanProductRepository.findByCodeIgnoreCase("SALARY-PLUS");
        var merchantFlex = loanProductRepository.findByCodeIgnoreCase("MERCHANT-FLEX");
        var educationAccess = loanProductRepository.findByCodeIgnoreCase("EDU-ACCESS");

        assertThat(salaryPlus).isPresent();
        assertThat(merchantFlex).isPresent();
        assertThat(educationAccess).isPresent();

        assertThat(loanProductLspMappingRepository.findAllByLoanProduct_Id(salaryPlus.orElseThrow().getId()))
                .hasSize(2);
        assertThat(loanProductLspMappingRepository.findAllByLoanProduct_Id(merchantFlex.orElseThrow().getId()))
                .hasSize(2);
        assertThat(loanProductLspMappingRepository.findAllByLoanProduct_Id(educationAccess.orElseThrow().getId()))
                .hasSize(1);
        assertThat(loanProductAuditEventRepository.count()).isGreaterThanOrEqualTo(6);
    }
}
