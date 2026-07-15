package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.PortfolioMisReadRepository;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest
@ActiveProfiles("test")
@Import({AdminReportingService.class, PortfolioMisReadRepository.class})
class AdminReportingServicePortfolioMisExportStreamingTest {

    @Autowired
    private AdminReportingService adminReportingService;

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
    private LoanProductVersionRepository loanProductVersionRepository;

    @Autowired
    private LspRepository lspRepository;

    @MockitoBean
    private LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;

    @MockitoBean
    private BusinessCalendar businessCalendar;

    @Test
    void streamingCsvMatchesRowAggregationOnSeededPortfolio() {
        when(businessCalendar.today()).thenReturn(LocalDate.of(2026, 7, 6));

        Lsp lsp = lspRepository.save(new Lsp("APEX", "Apex Finance", LspStatus.ACTIVE));
        LoanProduct product = persistProduct(product("PORT-STREAM", new BigDecimal("18.50")));

        LoanAccount first = loanAccountRepository.save(disbursedAccount(
                application(borrower(lsp, "Anika Sharma", "ABCDE1234F"), lsp, product, "APEX-LOAN-001", LoanApplicationStatus.DISBURSED),
                "ACCT-APEX-001",
                new BigDecimal("1000.00"),
                LocalDate.of(2026, 3, 10)
        ));
        loanRepaymentScheduleInstallmentRepository.save(new LoanRepaymentScheduleInstallment(
                first,
                1,
                LocalDate.of(2026, 4, 10),
                new BigDecimal("1000.00"),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("1000.00"),
                new BigDecimal("1000.00"),
                BigDecimal.ZERO.setScale(2)
        ));

        loanAccountRepository.save(disbursedAccount(
                application(borrower(lsp, "Rahul Shah", "ZXCVB1234N"), lsp, product, "APEX-LOAN-002", LoanApplicationStatus.DISBURSED),
                "ACCT-APEX-002",
                new BigDecimal("3000.00"),
                LocalDate.of(2026, 4, 5)
        ));

        List<AdminReportingService.PortfolioMisRow> rows = adminReportingService.buildPortfolioMisReport(null, null, null);
        String expectedCsv = PortfolioMisCsvWriter.toCsv(rows);

        AdminReportingService.GeneratedReport generated = adminReportingService.generatePortfolioMisCsv(null, null, null);
        String actualCsv = new String(generated.content(), StandardCharsets.UTF_8);

        assertThat(actualCsv).isEqualTo(expectedCsv);
        assertThat(actualCsv).contains("APEX-LOAN-001", "APEX-LOAN-002", "ACCT-APEX-001", "ACCT-APEX-002");
    }

    private Borrower borrower(Lsp lsp, String fullName, String pan) {
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
                application.getLoanProductVersion(),
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
