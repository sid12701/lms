package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
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
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class LoanAutoApprovalConcurrencyPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository checklistRepository;

    @Autowired
    private LoanApplicationLifecycleService lifecycleService;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanProductLspMappingRepository mappingRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LoanProductVersionRepository loanProductVersionRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void concurrentCrossLspApprovalsForSameBorrowerCreateExactlyOneOpenLoanAccount() throws Exception {
        Fixture fixture = new TransactionTemplate(transactionManager).execute(status -> createFixture());
        assertThat(fixture).isNotNull();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<LoanApplicationStatus>> approvals = List.of(
                    executor.submit(() -> approveWhenReleased(fixture.firstApplicationId(), ready, start)),
                    executor.submit(() -> approveWhenReleased(fixture.secondApplicationId(), ready, start))
            );

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both approval tasks must be ready");
            start.countDown();

            List<LoanApplicationStatus> results = List.of(
                    approvals.get(0).get(15, TimeUnit.SECONDS),
                    approvals.get(1).get(15, TimeUnit.SECONDS)
            );

            assertThat(results).containsExactlyInAnyOrder(
                    LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                    LoanApplicationStatus.REJECTED
            );
        }

        TenantScopedExecution.runAsAdmin(() -> {
            assertThat(loanAccountRepository.count()).isEqualTo(1);
            assertThat(List.of(
                    loanApplicationRepository.findById(fixture.firstApplicationId()).orElseThrow().getStatus(),
                    loanApplicationRepository.findById(fixture.secondApplicationId()).orElseThrow().getStatus()
            )).containsExactlyInAnyOrder(
                    LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                    LoanApplicationStatus.REJECTED
            );
        });
    }

    private LoanApplicationStatus approveWhenReleased(
            UUID applicationId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent approval");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent approval was interrupted", exception);
        }
        return TenantScopedExecution.callAsAdmin(
                () -> lifecycleService.autoApproveIfEligibleForLsp(applicationId, "lsp.api").getStatus());
    }

    private Fixture createFixture() {
        String fixtureSuffix = UUID.randomUUID().toString().substring(0, 8);
        Lsp firstLsp = lspRepository.save(new Lsp(
                "APPROVAL-CONC-A-" + fixtureSuffix,
                "Approval concurrency LSP A",
                LspStatus.ACTIVE
        ));
        Lsp secondLsp = lspRepository.save(new Lsp(
                "APPROVAL-CONC-B-" + fixtureSuffix,
                "Approval concurrency LSP B",
                LspStatus.ACTIVE
        ));
        LoanProduct product = loanProductRepository.save(new LoanProduct(
                "APPROVAL-CONC-" + UUID.randomUUID().toString().substring(0, 8),
                "Approval concurrency product",
                new BigDecimal("5000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("18.50"),
                new BigDecimal("2.25"),
                6,
                24,
                LoanProductStatus.ACTIVE
        ));
        LoanProductVersion productVersion = loanProductVersionRepository.save(
                LoanProductVersionTestSupport.versionOne(product));
        mappingRepository.saveAll(List.of(
                new LoanProductLspMapping(product, firstLsp, true),
                new LoanProductLspMapping(product, secondLsp, true)
        ));

        Borrower borrower = borrowerRepository.save(new Borrower(BorrowerProfile.builder()
                .fullName("Concurrent Approval Borrower")
                .emailAddress("approval-concurrency@example.com")
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
                .build()
        ));

        LoanApplication first = loanApplicationRepository.save(application(
                borrower, firstLsp, product, productVersion, "CONCURRENT-A"));
        LoanApplication second = loanApplicationRepository.save(application(
                borrower, secondLsp, product, productVersion, "CONCURRENT-B"));
        checklistRepository.saveAll(submittedChecklist(first));
        checklistRepository.saveAll(submittedChecklist(second));
        return new Fixture(first.getId(), second.getId());
    }

    private static LoanApplication application(
            Borrower borrower,
            Lsp lsp,
            LoanProduct product,
            LoanProductVersion productVersion,
            String externalLoanId
    ) {
        return new LoanApplication(
                borrower,
                lsp,
                product,
                productVersion,
                externalLoanId + "-" + UUID.randomUUID().toString().substring(0, 8),
                "API",
                new BigDecimal("45000.00"),
                12,
                LoanApplicationStatus.AWAITING_APPROVAL
        );
    }

    private static List<LoanApplicationDocumentChecklist> submittedChecklist(LoanApplication application) {
        return Arrays.stream(LoanApplicationDocumentType.values())
                .map(documentType -> new LoanApplicationDocumentChecklist(
                        application,
                        documentType,
                        documentType.isRequiredByDefault(),
                        LoanApplicationDocumentChecklistStatus.SUBMITTED,
                        "Submitted for approval concurrency test",
                        "test"
                ))
                .toList();
    }

    private record Fixture(UUID firstApplicationId, UUID secondApplicationId) {
    }
}
