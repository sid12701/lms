package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
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
class LoanRepaymentScheduleInstallmentRepositoryPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository installmentRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @Test
    void deleteByLoanAccountIdRemovesOnlyTargetAccountInstallments() {
        Lsp lsp = lspRepository.save(new Lsp("INST-DEL-LSP", "Installment Delete LSP", LspStatus.ACTIVE));
        LoanProduct product = loanProductRepository.save(product("INST-DEL-PROD"));
        LoanAccount targetAccount = persistAccount(lsp, product, "INST-DEL-A", "ACCT-DEL-A");
        LoanAccount otherAccount = persistAccount(lsp, product, "INST-DEL-B", "ACCT-DEL-B");

        installmentRepository.saveAll(List.of(
                installment(targetAccount, 1),
                installment(targetAccount, 2),
                installment(targetAccount, 3),
                installment(otherAccount, 1)
        ));

        installmentRepository.deleteByLoanAccountId(targetAccount.getId());
        installmentRepository.flush();

        assertThat(installmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(targetAccount.getId()))
                .isEmpty();
        assertThat(installmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(otherAccount.getId()))
                .extracting(LoanRepaymentScheduleInstallment::getInstallmentNumber)
                .containsExactly(1);
    }

    @Test
    void deleteByLoanAccountIdReturnsNumberOfRowsRemoved() {
        Lsp lsp = lspRepository.save(new Lsp("INST-CNT-LSP", "Installment Count LSP", LspStatus.ACTIVE));
        LoanProduct product = loanProductRepository.save(product("INST-CNT-PROD"));
        LoanAccount account = persistAccount(lsp, product, "INST-CNT-A", "ACCT-CNT-A");

        installmentRepository.saveAll(List.of(
                installment(account, 1),
                installment(account, 2),
                installment(account, 3)
        ));
        installmentRepository.flush();

        long removed = installmentRepository.deleteByLoanAccountId(account.getId());
        installmentRepository.flush();

        assertThat(removed).isEqualTo(3L);
    }

    @Test
    void deleteByLoanAccountIdReturnsZeroWhenNoRowsMatch() {
        long removed = installmentRepository.deleteByLoanAccountId(UUID.randomUUID());

        assertThat(removed).isZero();
    }

    private LoanAccount persistAccount(Lsp lsp, LoanProduct product, String externalLoanId, String accountNumber) {
        Borrower borrower = borrowerRepository.save(new Borrower(
                lsp,
                "Borrower " + externalLoanId,
                "ABCDE" + UUID.randomUUID().toString().substring(0, 4).toUpperCase() + "F",
                "9000000000",
                externalLoanId.toLowerCase() + "@example.com",
                LocalDate.of(1992, 1, 1),
                "Bengaluru",
                "Karnataka",
                "SALARIED",
                new BigDecimal("75000.00")
        ));
        LoanApplication application = loanApplicationRepository.save(new LoanApplication(
                borrower,
                lsp,
                product,
                externalLoanId,
                "API",
                new BigDecimal("50000.00"),
                12,
                LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
        ));
        return loanAccountRepository.save(new LoanAccount(
                application,
                borrower,
                lsp,
                product,
                accountNumber,
                new BigDecimal("50000.00"),
                12,
                LoanAccountStatus.PENDING_DISBURSEMENT,
                Instant.parse("2026-03-01T00:00:00Z")
        ));
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

    private LoanRepaymentScheduleInstallment installment(LoanAccount account, int installmentNumber) {
        return new LoanRepaymentScheduleInstallment(
                account,
                installmentNumber,
                LocalDate.of(2026, 6, installmentNumber),
                new BigDecimal("50000.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("4500.00"),
                new BigDecimal("46000.00")
        );
    }
}
