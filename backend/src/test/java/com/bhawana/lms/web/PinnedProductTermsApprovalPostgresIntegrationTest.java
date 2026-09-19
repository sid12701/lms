package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.service.LoanApplicationDocumentChecklistService;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanApplicationOnboardingCommand;
import com.bhawana.lms.service.LoanAutoApprovalRuleEngine;
import com.bhawana.lms.service.LoanAutoApprovalRuleEngine.RuleCode;
import com.bhawana.lms.service.ProductConfigurationService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H16: approval eligibility reads commercial bounds from the application's pinned product
 * version; live product/LSP/mapping status stays the availability kill switch.
 */
@SpringBootTest
@ActiveProfiles("test")
class PinnedProductTermsApprovalPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LspRepository lspRepository;
    @Autowired private LoanProductVersionRepository loanProductVersionRepository;
    @Autowired private LoanProductLspMappingRepository mappingRepository;
    @Autowired private BorrowerRepository borrowerRepository;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private ProductConfigurationService productConfigurationService;
    @Autowired private LoanApplicationDocumentChecklistService documentChecklistService;
    @Autowired private LoanApplicationLifecycleService lifecycleService;
    @Autowired private LoanAutoApprovalRuleEngine ruleEngine;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void approvalUsesTheSubmittedVersionAfterTheCatalogIsTightened() {
        Fixture fixture = createFixture(new BigDecimal("200000.00"), 18);
        UUID versionA = fixture.versionId();

        // Version B no longer admits 200000 over 18 months.
        editProduct(fixture, new BigDecimal("100000.00"), 12);
        assertThat(latestVersionNumber(fixture)).isEqualTo(2);

        assertThat(evaluate(fixture).failedRules()).isEmpty();

        LoanApplication approved = TenantScopedExecution.callAsAdmin(() ->
                lifecycleService.autoApproveIfEligibleForLsp(fixture.applicationId(), "lsp.api"));
        assertThat(approved.getStatus()).isEqualTo(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT loan_product_version_id FROM loan_account WHERE loan_application_id = ?",
                UUID.class, fixture.applicationId())).isEqualTo(versionA);
    }

    @Test
    void widenedCatalogDoesNotRescueAnApplicationOutsideItsPinnedTerms() {
        Fixture fixture = createFixture(new BigDecimal("45000.00"), 12);
        // Terms that never qualified under version A (max 250000, 6..24 months).
        jdbcTemplate.update("UPDATE loan_application SET requested_amount = 300000.00, tenure_months = 30 WHERE id = ?",
                fixture.applicationId());

        editProduct(fixture, new BigDecimal("500000.00"), 36);

        assertThat(evaluate(fixture).failedRules())
                .containsExactlyInAnyOrder(RuleCode.LOAN_AMOUNT_OUT_OF_RANGE, RuleCode.LOAN_TENURE_OUT_OF_RANGE);
    }

    @Test
    void disablingProductLspOrMappingStillBlocksApproval() {
        Fixture fixture = createFixture(new BigDecimal("45000.00"), 12);
        assertThat(evaluate(fixture).failedRules()).isEmpty();

        jdbcTemplate.update("UPDATE loan_product SET status = 'INACTIVE' WHERE id = ?", fixture.productId());
        assertThat(evaluate(fixture).failedRules()).contains(RuleCode.PRODUCT_INACTIVE);
        jdbcTemplate.update("UPDATE loan_product SET status = 'ACTIVE' WHERE id = ?", fixture.productId());

        jdbcTemplate.update("UPDATE loan_product_lsp_mapping SET enabled = false WHERE lsp_id = ?", fixture.lspId());
        assertThat(evaluate(fixture).failedRules()).contains(RuleCode.LSP_PRODUCT_MAPPING_INACTIVE);
        jdbcTemplate.update("UPDATE loan_product_lsp_mapping SET enabled = true WHERE lsp_id = ?", fixture.lspId());

        jdbcTemplate.update("UPDATE lsp SET status = 'INACTIVE' WHERE id = ?", fixture.lspId());
        assertThat(evaluate(fixture).failedRules()).contains(RuleCode.LSP_INACTIVE);
    }

    @Test
    void newApplicationPinsAndIsValidatedAgainstTheLatestVersion() {
        Fixture fixture = createFixture(new BigDecimal("45000.00"), 12);
        editProduct(fixture, new BigDecimal("100000.00"), 12);
        UUID versionB = jdbcTemplate.queryForObject(
                "SELECT id FROM loan_product_version WHERE loan_product_id = ? AND version_number = 2",
                UUID.class, fixture.productId());

        // Allowed by version A, not by B: intake refuses it.
        assertThatThrownBy(() -> onboard(fixture, new BigDecimal("150000.00"), 12, "PIN-OUT"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("errorCode").isEqualTo("AMOUNT_OUT_OF_RANGE");

        LoanApplication created = onboard(fixture, new BigDecimal("90000.00"), 12, "PIN-IN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT loan_product_version_id FROM loan_application WHERE id = ?", UUID.class, created.getId()))
                .isEqualTo(versionB);
    }

    private LoanAutoApprovalRuleEngine.Evaluation evaluate(Fixture fixture) {
        return TenantScopedExecution.callAsAdmin(() -> transactionTemplate.execute(status ->
                ruleEngine.evaluate(loanApplicationRepository.findDetailedById(fixture.applicationId()).orElseThrow())));
    }

    private void editProduct(Fixture fixture, BigDecimal maxPrincipal, int maxTenureMonths) {
        TenantScopedExecution.callAsAdmin(() -> productConfigurationService.updateProduct(
                fixture.productId(),
                fixture.productCode(),
                "Pinned terms product",
                new BigDecimal("5000.00"),
                maxPrincipal,
                new BigDecimal("18.50"),
                new BigDecimal("2.25"),
                6,
                maxTenureMonths,
                LoanProductStatus.ACTIVE
        ));
    }

    private int latestVersionNumber(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT max(version_number) FROM loan_product_version WHERE loan_product_id = ?",
                Integer.class, fixture.productId());
    }

    private void completeDocuments(Fixture fixture) {
        TenantScopedExecution.callAsAdmin(() -> {
            for (LoanApplicationDocumentType type : LoanApplicationDocumentType.values()) {
                documentChecklistService.updateDocumentChecklistItem(
                        fixture.applicationId(), type, "test", LoanApplicationDocumentChecklistStatus.SUBMITTED,
                        "submitted", type.name().toLowerCase() + ".pdf", "https://example.test/" + type.name(),
                        null, "application/pdf", null, null, null, false);
            }
            return null;
        });
    }

    private LoanApplication onboard(Fixture fixture, BigDecimal amount, int tenure, String externalId) {
        return TenantScopedExecution.callAsAdmin(() -> lifecycleService.createApplication(
                "ops.user",
                new LoanApplicationOnboardingCommand(
                        fixture.lspId(), fixture.productId(), null, externalId + "-" + UUID.randomUUID(), "API",
                        amount, new BigDecimal("18.50"), tenure, borrowerProfile("onboard-" + externalId))
        ));
    }

    private Fixture createFixture(BigDecimal amount, int tenure) {
        Fixture fixture = TenantScopedExecution.callAsAdmin(() -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            LoanProduct product = productConfigurationService.createProduct(
                    "PIN-" + suffix,
                    "Pinned terms product",
                    new BigDecimal("5000.00"),
                    new BigDecimal("250000.00"),
                    new BigDecimal("18.50"),
                    new BigDecimal("2.25"),
                    6,
                    24,
                    LoanProductStatus.ACTIVE
            );
            return transactionTemplate.execute(status -> {
                Lsp lsp = lspRepository.save(new Lsp("PIN-" + suffix, "Pinned LSP " + suffix, LspStatus.ACTIVE));
                mappingRepository.save(new LoanProductLspMapping(product, lsp, true));
                LoanProductVersion versionA = loanProductVersionRepository
                        .findTopByLoanProduct_IdOrderByVersionNumberDesc(product.getId())
                        .orElseThrow();
                Borrower borrower = borrowerRepository.save(new Borrower(borrowerProfile("fixture-" + suffix)));
                LoanApplication application = loanApplicationRepository.save(new LoanApplication(
                        borrower, lsp, product, versionA, "PIN-" + suffix, "API", amount, tenure,
                        LoanApplicationStatus.AWAITING_APPROVAL));
                documentChecklistService.seedDocumentChecklist(application, "test");
                return new Fixture(lsp.getId(), product.getId(), product.getCode(), versionA.getId(), application.getId());
            });
        });
        // Documents complete, so the only rules in play are the ones under test.
        completeDocuments(fixture);
        return fixture;
    }

    private static BorrowerProfile borrowerProfile(String tag) {
        return BorrowerProfile.builder()
                .fullName("Pinned Terms Borrower")
                .emailAddress(tag + "@example.com")
                .mobileNumber("9876543210")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .aadharNumber("123456789012")
                .panNumber("ABCDE1234F")
                .addressLine1("1 Test Street")
                .addressCity("Mumbai")
                .addressState("Maharashtra")
                .addressZipcode("400001")
                .employmentStatus("SALARIED")
                .monthlyIncome(new BigDecimal("50000.00"))
                .annualIncome(new BigDecimal("600000.00"))
                .referencePersonName("Reference Person")
                .referencePersonNumber("9876500000")
                .build();
    }

    private record Fixture(UUID lspId, UUID productId, String productCode, UUID versionId, UUID applicationId) {
    }
}
