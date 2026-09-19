package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.DisbursementReconciliationQueueEntry;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * H25: worker scans select bounded due IDs, and the status-check scan only owns accounts with
 * no reconciliation queue entry — repeats run on the queue's {@code next_poll_at} backoff via
 * the reconciliation sweep instead of being re-polled every tick.
 */
@DataJpaTest
@ActiveProfiles("test")
class DisbursementScanBoundTest {

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LoanProductVersionRepository loanProductVersionRepository;

    @Autowired
    private LspRepository lspRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void applicationScanReturnsOnlyTheRequestedNumberOfDueIds() {
        Lsp lsp = lspRepository.save(new Lsp("BOUND", "Bound Finance", LspStatus.ACTIVE));
        LoanProduct product = persistProduct(product("BOUND-1"));
        for (int i = 0; i < 4; i++) {
            application(
                    borrower("Borrower " + i, "ABCDE" + (1000 + i) + "F"),
                    lsp,
                    product,
                    "BOUND-LOAN-" + i,
                    LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
            );
        }

        List<UUID> firstPage = loanApplicationRepository.findIdsByStatus(
                LoanApplicationStatus.APPROVED_PENDING_DISBURSAL, PageRequest.of(0, 3));
        List<UUID> full = loanApplicationRepository.findIdsByStatus(
                LoanApplicationStatus.APPROVED_PENDING_DISBURSAL, PageRequest.of(0, 100));

        assertThat(firstPage).hasSize(3);
        // Deterministic database ordering: the bounded page is a stable prefix of the full
        // result, so consecutive ticks drain the same queue without repeating work.
        assertThat(firstPage).isEqualTo(full.subList(0, 3));
    }

    @Test
    void statusCheckScanSelectsOnlyAccountsWithoutAQueueEntry() {
        Lsp lsp = lspRepository.save(new Lsp("SCAN", "Scan Finance", LspStatus.ACTIVE));
        LoanProduct product = persistProduct(product("SCAN-1"));

        LoanAccount freshAccount = loanAccountRepository.save(requestedAccount(
                application(borrower("Ishaan Rao", "ABCDE2001F"), lsp, product, "SCAN-LOAN-001",
                        LoanApplicationStatus.DISBURSED),
                "ACCT-SCAN-001"
        ));
        LoanAccount queuedAccount = loanAccountRepository.save(requestedAccount(
                application(borrower("Meera Iyer", "ABCDE2002F"), lsp, product, "SCAN-LOAN-002",
                        LoanApplicationStatus.DISBURSED),
                "ACCT-SCAN-002"
        ));
        // A still-pending account gets a queue row + backoff on its first unresolved poll;
        // repeats belong to the reconciliation sweep's next_poll_at schedule.
        DisbursementReconciliationQueueEntry entry = new DisbursementReconciliationQueueEntry(
                queuedAccount,
                null,
                "REF-SCAN-002",
                DisbursementReconciliationReason.REQUESTED,
                Instant.now().plusSeconds(3600),
                "poll pending");
        entityManager.persist(entry);
        entityManager.flush();

        List<UUID> candidates = loanAccountRepository
                .findIdsAwaitingFirstStatusPoll(PageRequest.of(0, 100));

        assertThat(candidates)
                .contains(freshAccount.getLoanApplication().getId())
                .doesNotContain(queuedAccount.getLoanApplication().getId());
    }

    @Test
    void statusCheckScanHonoursThePageBound() {
        Lsp lsp = lspRepository.save(new Lsp("PAGE", "Page Finance", LspStatus.ACTIVE));
        LoanProduct product = persistProduct(product("PAGE-1"));
        for (int i = 0; i < 3; i++) {
            loanAccountRepository.save(requestedAccount(
                    application(borrower("Borrower " + i, "ABCDE" + (3000 + i) + "F"), lsp, product,
                            "PAGE-LOAN-" + i, LoanApplicationStatus.DISBURSED),
                    "ACCT-PAGE-" + i
            ));
        }

        List<UUID> candidates = loanAccountRepository
                .findIdsAwaitingFirstStatusPoll(PageRequest.of(0, 2));

        assertThat(candidates).hasSize(2);
    }

    private Borrower borrower(String fullName, String pan) {
        return borrowerRepository.save(new Borrower(BorrowerProfile.builder()
                        .fullName(fullName)
                        .panNumber(pan)
                        .mobileNumber("9000000000")
                        .emailAddress(fullName.replace(' ', '.').toLowerCase() + "@example.com")
                        .dateOfBirth(LocalDate.of(1992, 3, 10))
                        .addressCity("Mumbai")
                        .addressState("Maharashtra")
                        .employmentStatus("SALARIED")
                        .monthlyIncome(new BigDecimal("80000.00"))
                        .build()
        ));
    }

    private LoanApplication application(
            Borrower borrower,
            Lsp lsp,
            LoanProduct product,
            String externalLoanId,
            LoanApplicationStatus status
    ) {
        LoanProductVersion version = loanProductVersionRepository
                .findTopByLoanProduct_IdOrderByVersionNumberDesc(product.getId())
                .orElseThrow();
        return loanApplicationRepository.save(new LoanApplication(
                borrower,
                lsp,
                product,
                version,
                externalLoanId,
                "API",
                new BigDecimal("50000.00"),
                12,
                status
        ));
    }

    private LoanProduct persistProduct(LoanProduct product) {
        LoanProduct saved = loanProductRepository.save(product);
        loanProductVersionRepository.save(LoanProductVersionTestSupport.versionOne(saved));
        return saved;
    }

    private LoanProduct product(String code) {
        return new LoanProduct(
                code,
                "Product " + code,
                new BigDecimal("5000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("18.50"),
                new BigDecimal("2.00"),
                6,
                24,
                LoanProductStatus.ACTIVE
        );
    }

    private LoanAccount requestedAccount(LoanApplication application, String accountNumber) {
        LoanAccount loanAccount = new LoanAccount(
                application,
                application.getBorrower(),
                application.getLsp(),
                application.getLoanProduct(),
                application.getLoanProductVersion(),
                accountNumber,
                new BigDecimal("1000.00"),
                application.getTenureMonths(),
                LoanAccountStatus.PENDING_DISBURSEMENT,
                Instant.parse("2026-03-01T00:00:00Z")
        );
        loanAccount.requestDisbursement();
        return loanAccount;
    }
}
