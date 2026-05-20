package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PortfolioMisReadRepository.class)
class PortfolioMisReadRepositoryPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private PortfolioMisReadRepository portfolioMisReadRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @Test
    void pagesAccountsUsingDatabaseOrderingAndCountingOnPostgres() {
        Lsp apex = lspRepository.save(new Lsp("APEX-PORTFOLIO", "Apex Finance", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("NORTH-PORTFOLIO", "Northbridge Capital", LspStatus.ACTIVE));
        LoanProduct product = loanProductRepository.save(product("PORTFOLIO-PG-1", new BigDecimal("18.50")));

        LoanAccount apexAccount = loanAccountRepository.save(disbursedAccount(
                application(borrower(apex, "Anika Sharma", "ABCDE1234F"), apex, product, "APEX-LOAN-001", LoanApplicationStatus.DISBURSED),
                "ACCT-APEX-001",
                new BigDecimal("1000.00"),
                LocalDate.of(2026, 3, 10)
        ));
        loanAccountRepository.save(disbursedAccount(
                application(borrower(north, "Rahul Shah", "ZXCVB1234N"), north, product, "NORTH-LOAN-001", LoanApplicationStatus.CLOSED),
                "ACCT-NORTH-001",
                new BigDecimal("3000.00"),
                LocalDate.of(2026, 4, 5)
        ));

        PortfolioMisReadRepository.PortfolioMisAccountPage page = portfolioMisReadRepository.findAccountsPage(
                null,
                null,
                null,
                0,
                1
        );

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().getId()).isEqualTo(apexAccount.getId());
    }

    @Test
    void summarizesPortfolioMetricsWithTypedAggregateResultsOnPostgres() {
        Lsp lsp = lspRepository.save(new Lsp("SUPAONE-PORTFOLIO", "Supa One Finance", LspStatus.ACTIVE));
        LoanProduct productTen = loanProductRepository.save(product("SUPA-FLEX-PG", new BigDecimal("10.00")));
        LoanProduct productTwenty = loanProductRepository.save(product("SUPA-MAX-PG", new BigDecimal("20.00")));

        LoanAccount activeAccount = loanAccountRepository.save(disbursedAccount(
                application(borrower(lsp, "Aman Verma", "ABCDE1001F"), lsp, productTen, "SUPA-1001", LoanApplicationStatus.DISBURSED),
                "ACCT-1001",
                new BigDecimal("1000.00"),
                LocalDate.of(2026, 3, 10)
        ));
        loanRepaymentScheduleInstallmentRepository.save(new LoanRepaymentScheduleInstallment(
                activeAccount,
                1,
                LocalDate.now(ZoneOffset.UTC).minusDays(45),
                new BigDecimal("1000.00"),
                new BigDecimal("800.00"),
                new BigDecimal("200.00"),
                new BigDecimal("1000.00"),
                BigDecimal.ZERO.setScale(2)
        ));

        LoanAccount closedAccount = disbursedAccount(
                application(borrower(lsp, "Bhavna Rao", "ABCDE1002F"), lsp, productTwenty, "SUPA-1002", LoanApplicationStatus.CLOSED),
                "ACCT-1002",
                new BigDecimal("3000.00"),
                LocalDate.of(2026, 4, 5)
        );
        closedAccount.close(LoanAccountClosureReason.FULLY_REPAID, "system", Instant.parse("2026-05-01T00:00:00Z"));
        loanAccountRepository.save(closedAccount);

        PortfolioMisSummaryAggregate aggregate = portfolioMisReadRepository.summarize(
                lsp.getId(),
                null,
                null,
                LocalDate.now(ZoneOffset.UTC).minusDays(30)
        );

        assertThat(aggregate.totalDisbursed()).isEqualByComparingTo("4000.00");
        assertThat(aggregate.activeLoanCount()).isEqualTo(1L);
        assertThat(aggregate.weightedInterestAmount()).isEqualByComparingTo("70000.00");
        assertThat(aggregate.weightedPrincipalAmount()).isEqualByComparingTo("4000.00");
        assertThat(aggregate.atRiskPrincipal()).isEqualByComparingTo("1000.00");
        assertThat(aggregate.totalLoanCount()).isEqualTo(2L);
    }

    private Borrower borrower(Lsp lsp, String fullName, String pan) {
        return borrowerRepository.save(new Borrower(
                lsp,
                fullName,
                pan,
                "9000000000",
                fullName.replace(' ', '.').toLowerCase() + "@example.com",
                LocalDate.of(1992, 3, 10),
                "Mumbai",
                "Maharashtra",
                "SALARIED",
                new BigDecimal("80000.00")
        ));
    }

    private LoanApplication application(
            Borrower borrower,
            Lsp lsp,
            LoanProduct product,
            String externalLoanId,
            LoanApplicationStatus status
    ) {
        return loanApplicationRepository.save(new LoanApplication(
                borrower,
                lsp,
                product,
                externalLoanId,
                "API",
                new BigDecimal("50000.00"),
                12,
                status
        ));
    }

    private LoanProduct product(String code, BigDecimal interestRate) {
        return new LoanProduct(
                code,
                "Product " + code,
                new BigDecimal("5000.00"),
                new BigDecimal("250000.00"),
                interestRate,
                new BigDecimal("2.00"),
                6,
                24,
                LoanProductStatus.ACTIVE
        );
    }

    private LoanAccount disbursedAccount(
            LoanApplication application,
            String accountNumber,
            BigDecimal principalAmount,
            LocalDate disbursedDate
    ) {
        LoanAccount loanAccount = new LoanAccount(
                application,
                application.getBorrower(),
                application.getLsp(),
                application.getLoanProduct(),
                accountNumber,
                principalAmount,
                application.getTenureMonths(),
                LoanAccountStatus.PENDING_DISBURSEMENT,
                Instant.parse("2026-03-01T00:00:00Z")
        );
        loanAccount.updateDisbursementStatus(
                LoanAccountStatus.DISBURSED,
                disbursedDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        );
        return loanAccount;
    }
}
