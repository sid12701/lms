package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LoanApplicationReadRepository.class)
class LoanApplicationReadRepositoryPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private LoanApplicationReadRepository loanApplicationReadRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @Test
    void filtersApplicationsByStructuredParametersAndSearchQueryOnPostgres() {
        Lsp apex = lspRepository.save(new Lsp("APEX-PG", "Apex Finance", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("NORTH-PG", "Northbridge Capital", LspStatus.ACTIVE));
        LoanProduct salaryProduct = loanProductRepository.save(product("SALARY-PG", "Salary Plus"));
        LoanProduct merchantProduct = loanProductRepository.save(product("MERCHANT-PG", "Merchant Flex"));

        loanApplicationRepository.save(application(
                borrower(apex, "Anika Sharma", "ABCDE1234F", "9999999999", "Bengaluru"),
                apex,
                salaryProduct,
                "APEX-100",
                "API",
                LoanApplicationStatus.INITIALIZED
        ));
        LoanApplication matchingApplication = loanApplicationRepository.save(application(
                borrower(north, "Rahul Shah", "ZXCVB1234N", "9876543210", "Delhi"),
                north,
                merchantProduct,
                "NORTH-200",
                "PARTNER_PORTAL",
                LoanApplicationStatus.INITIALIZED
        ));
        loanApplicationRepository.save(application(
                borrower(north, "Rahul Mehta", "QWERT1234P", "9811111111", "Delhi"),
                north,
                merchantProduct,
                "NORTH-201",
                "API",
                LoanApplicationStatus.REJECTED
        ));

        List<LoanApplication> results = loanApplicationReadRepository.findApplications(
                north.getId(),
                merchantProduct.getId(),
                LoanApplicationStatus.INITIALIZED,
                "PARTNER_PORTAL",
                "rahul",
                null,
                null,
                null
        ).items();

        assertThat(results)
                .extracting(LoanApplication::getId)
                .containsExactly(matchingApplication.getId());
    }

    @Test
    void filtersApplicationsByDisbursalDateRangeOnPostgres() {
        Lsp lsp = lspRepository.save(new Lsp("SUPAONE-PG", "Supa One Finance", LspStatus.ACTIVE));
        LoanProduct product = loanProductRepository.save(product("SUPA-FLEX-PG", "Supa Flex"));

        LoanApplication marchApplication = loanApplicationRepository.save(application(
                borrower(lsp, "Aman Verma", "ABCDE1001F", "9000001001", "Mumbai"),
                lsp,
                product,
                "SUPA-1001",
                "API",
                LoanApplicationStatus.DISBURSED
        ));
        LoanApplication aprilApplication = loanApplicationRepository.save(application(
                borrower(lsp, "Bhavna Rao", "ABCDE1002F", "9000001002", "Pune"),
                lsp,
                product,
                "SUPA-1002",
                "API",
                LoanApplicationStatus.DISBURSED
        ));

        loanAccountRepository.save(disbursedAccount(marchApplication, "ACCT-1001", new BigDecimal("85000.00"), LocalDate.of(2026, 3, 10)));
        loanAccountRepository.save(disbursedAccount(aprilApplication, "ACCT-1002", new BigDecimal("92000.00"), LocalDate.of(2026, 4, 5)));

        List<LoanApplication> results = loanApplicationReadRepository.findApplications(
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                LocalDate.of(2026, 4, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        ).items();

        assertThat(results)
                .extracting(LoanApplication::getExternalLoanId)
                .containsExactly("SUPA-1001");
    }

    private Borrower borrower(Lsp lsp, String fullName, String pan, String mobile, String city) {
        return borrowerRepository.save(new Borrower(
                lsp,
                BorrowerProfile.builder()
                        .fullName(fullName)
                        .panNumber(pan)
                        .mobileNumber(mobile)
                        .emailAddress(fullName.replace(' ', '.').toLowerCase() + "@example.com")
                        .dateOfBirth(LocalDate.of(1992, 3, 10))
                        .addressCity(city)
                        .addressState("Maharashtra")
                        .employmentStatus("SALARIED")
                        .monthlyIncome(new BigDecimal("75000.00"))
                        .build()
        ));
    }

    private LoanApplication application(
            Borrower borrower,
            Lsp lsp,
            LoanProduct product,
            String externalLoanId,
            String sourceChannel,
            LoanApplicationStatus status
    ) {
        return new LoanApplication(
                borrower,
                lsp,
                product,
                externalLoanId,
                sourceChannel,
                new BigDecimal("45000.00"),
                12,
                status
        );
    }

    private LoanProduct product(String code, String name) {
        return new LoanProduct(
                code,
                name,
                new BigDecimal("5000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("18.50"),
                new BigDecimal("2.25"),
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
