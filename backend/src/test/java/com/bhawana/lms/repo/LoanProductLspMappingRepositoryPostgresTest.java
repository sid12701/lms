package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LoanProductLspMappingRepositoryPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private LoanProductLspMappingRepository mappingRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @Test
    void deleteByLoanProductIdRemovesOnlyTargetProductMappings() {
        LoanProduct targetProduct = loanProductRepository.save(product("MAP-DEL-TARGET"));
        LoanProduct otherProduct = loanProductRepository.save(product("MAP-DEL-OTHER"));
        Lsp apex = lspRepository.save(new Lsp("MAP-DEL-APEX", "Apex", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("MAP-DEL-NORTH", "North", LspStatus.ACTIVE));

        mappingRepository.saveAll(List.of(
                new LoanProductLspMapping(targetProduct, apex, true),
                new LoanProductLspMapping(targetProduct, north, true),
                new LoanProductLspMapping(otherProduct, apex, true)
        ));
        mappingRepository.flush();

        long removed = mappingRepository.deleteByLoanProductId(targetProduct.getId());
        mappingRepository.flush();

        assertThat(removed).isEqualTo(2L);
        assertThat(mappingRepository.findAllByLoanProduct_Id(targetProduct.getId())).isEmpty();
        assertThat(mappingRepository.findAllByLoanProduct_Id(otherProduct.getId()))
                .extracting(m -> m.getLsp().getCode())
                .containsExactly("MAP-DEL-APEX");
    }

    @Test
    void deleteByLoanProductIdReturnsZeroWhenNoMappingsExist() {
        long removed = mappingRepository.deleteByLoanProductId(UUID.randomUUID());

        assertThat(removed).isZero();
    }

    private LoanProduct product(String code) {
        return new LoanProduct(
                code,
                code + " Product",
                new BigDecimal("5000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("18.50"),
                new BigDecimal("2.25"),
                6,
                24,
                LoanProductStatus.ACTIVE
        );
    }
}
