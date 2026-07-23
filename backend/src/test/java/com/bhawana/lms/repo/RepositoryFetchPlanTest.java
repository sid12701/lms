package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class RepositoryFetchPlanTest {

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private LoanProductLspMappingRepository mappingRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void resetStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void apiClientListingFetchesOwningLspsInOneStatement() {
        Lsp apex = lspRepository.save(new Lsp("FETCH-APEX", "Fetch Apex", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("FETCH-NORTH", "Fetch North", LspStatus.ACTIVE));
        apiClientRepository.saveAll(List.of(
                new ApiClient("fetch-client-1", apex, "Client 1", null, "hash-1", ApiClientStatus.ACTIVE),
                new ApiClient("fetch-client-2", north, "Client 2", null, "hash-2", ApiClientStatus.ACTIVE)
        ));
        flushAndResetStatistics();

        List<String> lspCodes = apiClientRepository.findAll().stream()
                .map(client -> client.getLsp().getCode())
                .sorted()
                .toList();

        assertThat(lspCodes).containsExactly("FETCH-APEX", "FETCH-NORTH");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @Test
    void mappingListingFetchesProductsAndLspsInOneStatement() {
        LoanProduct product = loanProductRepository.save(product("FETCH-LIST"));
        Lsp apex = lspRepository.save(new Lsp("FETCH-LIST-A", "Fetch List A", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("FETCH-LIST-N", "Fetch List N", LspStatus.ACTIVE));
        mappingRepository.saveAll(List.of(
                new LoanProductLspMapping(product, apex, true),
                new LoanProductLspMapping(product, north, true)
        ));
        flushAndResetStatistics();

        List<String> associations = mappingRepository.findAll().stream()
                .map(mapping -> mapping.getLoanProduct().getCode() + ":" + mapping.getLsp().getCode())
                .sorted()
                .toList();

        assertThat(associations).containsExactly("FETCH-LIST:FETCH-LIST-A", "FETCH-LIST:FETCH-LIST-N");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @Test
    void productMappingDetailFetchesAllLspsInOneStatement() {
        LoanProduct product = loanProductRepository.save(product("FETCH-DETAIL"));
        Lsp apex = lspRepository.save(new Lsp("FETCH-DETAIL-A", "Fetch Detail A", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("FETCH-DETAIL-N", "Fetch Detail N", LspStatus.ACTIVE));
        mappingRepository.saveAll(List.of(
                new LoanProductLspMapping(product, apex, true),
                new LoanProductLspMapping(product, north, true)
        ));
        flushAndResetStatistics();

        List<String> lspCodes = mappingRepository.findAllByLoanProduct_Id(product.getId()).stream()
                .map(mapping -> mapping.getLsp().getCode())
                .sorted()
                .toList();

        assertThat(lspCodes).containsExactly("FETCH-DETAIL-A", "FETCH-DETAIL-N");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    private void flushAndResetStatistics() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
    }

    private static LoanProduct product(String code) {
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
