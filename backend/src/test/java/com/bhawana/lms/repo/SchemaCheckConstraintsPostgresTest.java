package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaCheckConstraintsPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID lspId;
    private UUID borrowerId;
    private UUID loanProductId;
    private UUID loanApplicationId;
    private UUID loanAccountId;

    @BeforeEach
    void seedParents() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        lspId = insertLsp("LSP-" + suffix);
        borrowerId = insertBorrower("PAN" + suffix.substring(0, 7));
        loanProductId = insertLoanProductValid("PROD-" + suffix);
        loanApplicationId = insertLoanApplicationValid(borrowerId, lspId, loanProductId, "EXT-" + suffix);
        loanAccountId = insertLoanAccountValid(loanApplicationId, borrowerId, lspId, loanProductId, "ACC-" + suffix);
    }

    // ---------------------------------------------------------------------
    // loan_product invariants
    // ---------------------------------------------------------------------

    @Test
    void loanProductRejectsNegativeMinPrincipal() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-NEG-MIN", "-1.00", "1000.00", "10.00", "1.00", 6, 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanProductRejectsNegativeMaxPrincipal() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-NEG-MAX", "100.00", "-1.00", "10.00", "1.00", 6, 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanProductRejectsMinPrincipalGreaterThanMax() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-MIN-GT-MAX", "1000.00", "500.00", "10.00", "1.00", 6, 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanProductRejectsNegativeInterestRate() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-NEG-INT", "100.00", "1000.00", "-1.00", "1.00", 6, 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanProductRejectsNegativeProcessingFeeRate() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-NEG-FEE", "100.00", "1000.00", "10.00", "-1.00", 6, 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanProductRejectsNonPositiveMinTenureMonths() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-ZERO-MIN-T", "100.00", "1000.00", "10.00", "1.00", 0, 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanProductRejectsNonPositiveMaxTenureMonths() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-ZERO-MAX-T", "100.00", "1000.00", "10.00", "1.00", 6, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanProductRejectsMinTenureGreaterThanMaxTenure() {
        assertThatThrownBy(() -> insertLoanProduct("CHK-MIN-T-GT-MAX-T", "100.00", "1000.00", "10.00", "1.00", 12, 6))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // loan_application invariants
    // ---------------------------------------------------------------------

    @Test
    void loanApplicationRejectsNegativeRequestedAmount() {
        assertThatThrownBy(() -> insertLoanApplication(borrowerId, lspId, loanProductId, "EXT-NEG-REQ", "-1.00", 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanApplicationRejectsNonPositiveTenureMonths() {
        assertThatThrownBy(() -> insertLoanApplication(borrowerId, lspId, loanProductId, "EXT-ZERO-TENURE", "1000.00", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // loan_account invariants
    // ---------------------------------------------------------------------

    @Test
    void loanAccountRejectsNegativePrincipalAmount() {
        UUID freshApplication = insertLoanApplicationValid(borrowerId, lspId, loanProductId, "EXT-LA-NEG-PRIN-" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> insertLoanAccount(freshApplication, borrowerId, lspId, loanProductId, "ACC-NEG-PRIN", "-1.00", 12))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanAccountRejectsNonPositiveTenureMonths() {
        UUID freshApplication = insertLoanApplicationValid(borrowerId, lspId, loanProductId, "EXT-LA-ZERO-TEN-" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> insertLoanAccount(freshApplication, borrowerId, lspId, loanProductId, "ACC-ZERO-TEN", "1000.00", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // loan_repayment_schedule_installment invariants (incl. F-20 math)
    // ---------------------------------------------------------------------

    @Test
    void installmentRejectsNonPositiveInstallmentNumber() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 0,
                "1000.00", "100.00", "10.00", "110.00", "900.00",
                "0.00", "0.00", "0.00", "110.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativeOpeningPrincipal() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "-1.00", "100.00", "10.00", "110.00", "900.00",
                "0.00", "0.00", "0.00", "110.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativePrincipalDue() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "-1.00", "10.00", "110.00", "900.00",
                "0.00", "0.00", "0.00", "110.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativeInterestDue() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "-1.00", "110.00", "900.00",
                "0.00", "0.00", "0.00", "110.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativeInstallmentAmount() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "-1.00", "900.00",
                "0.00", "0.00", "0.00", "-1.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativeClosingPrincipal() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "110.00", "-1.00",
                "0.00", "0.00", "0.00", "110.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativePaidPrincipal() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "110.00", "900.00",
                "-1.00", "0.00", "-1.00", "111.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativePaidInterest() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "110.00", "900.00",
                "0.00", "-1.00", "-1.00", "111.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativePaidAmount() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "110.00", "900.00",
                "0.00", "0.00", "-1.00", "111.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsNegativeOutstandingAmount() {
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "110.00", "900.00",
                "0.00", "0.00", "0.00", "-1.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsPaidAmountNotEqualToPaidPrincipalPlusPaidInterest() {
        // 50 + 30 = 80, but paid_amount = 90 → violation.
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "110.00", "900.00",
                "50.00", "30.00", "90.00", "20.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void installmentRejectsPaidPlusOutstandingNotEqualToInstallmentAmount() {
        // paid_amount(80) + outstanding(20) = 100, but installment_amount = 110 → violation.
        assertThatThrownBy(() -> insertInstallment(loanAccountId, 1,
                "1000.00", "100.00", "10.00", "110.00", "900.00",
                "50.00", "30.00", "80.00", "20.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // loan_payment_transaction invariants
    // ---------------------------------------------------------------------

    @Test
    void loanPaymentTransactionRejectsNegativeAmount() {
        assertThatThrownBy(() -> insertLoanPaymentTransaction(loanAccountId, "-1.00", "0.00", "0.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanPaymentTransactionRejectsNegativeAllocatedAmount() {
        assertThatThrownBy(() -> insertLoanPaymentTransaction(loanAccountId, "100.00", "-1.00", "101.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanPaymentTransactionRejectsNegativeUnallocatedAmount() {
        assertThatThrownBy(() -> insertLoanPaymentTransaction(loanAccountId, "100.00", "101.00", "-1.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loanPaymentTransactionRejectsAllocatedPlusUnallocatedNotEqualToAmount() {
        // allocated(40) + unallocated(40) = 80, but amount = 100 → violation.
        assertThatThrownBy(() -> insertLoanPaymentTransaction(loanAccountId, "100.00", "40.00", "40.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private UUID insertLsp(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                id, code, "LSP " + code
        );
        return id;
    }

    private UUID insertBorrower(String pan) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile) VALUES (?, ?, ?, ?)",
                id, "Borrower " + pan, pan, "9999999999"
        );
        return id;
    }

    private UUID insertLoanProductValid(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_product (id, code, name, min_principal, max_principal, "
                        + "interest_rate, processing_fee_rate, min_tenure_months, max_tenure_months) "
                        + "VALUES (?, ?, ?, 100.00, 100000.00, 10.00, 1.00, 6, 60)",
                id, code, "Product " + code
        );
        return id;
    }

    private UUID insertLoanApplicationValid(UUID borrowerId, UUID lspId, UUID productId, String externalId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_application (id, borrower_id, lsp_id, loan_product_id, "
                        + "external_loan_id, source_channel, requested_amount, tenure_months, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'API', 5000.00, 12, 'INITIALIZED')",
                id, borrowerId, lspId, productId, externalId
        );
        return id;
    }

    private UUID insertLoanAccountValid(UUID applicationId, UUID borrowerId, UUID lspId, UUID productId, String accountNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_account (id, loan_application_id, borrower_id, lsp_id, loan_product_id, "
                        + "account_number, principal_amount, tenure_months, status, approved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 5000.00, 12, 'PENDING_DISBURSEMENT', NOW())",
                id, applicationId, borrowerId, lspId, productId, accountNumber
        );
        return id;
    }

    private void insertLoanProduct(
            String code,
            String minPrincipal,
            String maxPrincipal,
            String interestRate,
            String processingFeeRate,
            int minTenureMonths,
            int maxTenureMonths
    ) {
        jdbcTemplate.update(
                "INSERT INTO loan_product (code, name, min_principal, max_principal, "
                        + "interest_rate, processing_fee_rate, min_tenure_months, max_tenure_months) "
                        + "VALUES (?, ?, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?, ?)",
                code,
                "Product " + code,
                minPrincipal,
                maxPrincipal,
                interestRate,
                processingFeeRate,
                minTenureMonths,
                maxTenureMonths
        );
    }

    private void insertLoanApplication(
            UUID borrowerId,
            UUID lspId,
            UUID productId,
            String externalId,
            String requestedAmount,
            int tenureMonths
    ) {
        jdbcTemplate.update(
                "INSERT INTO loan_application (borrower_id, lsp_id, loan_product_id, "
                        + "external_loan_id, source_channel, requested_amount, tenure_months, status) "
                        + "VALUES (?, ?, ?, ?, 'API', ?::numeric, ?, 'INITIALIZED')",
                borrowerId, lspId, productId, externalId, requestedAmount, tenureMonths
        );
    }

    private void insertLoanAccount(
            UUID applicationId,
            UUID borrowerId,
            UUID lspId,
            UUID productId,
            String accountNumber,
            String principalAmount,
            int tenureMonths
    ) {
        jdbcTemplate.update(
                "INSERT INTO loan_account (id, loan_application_id, borrower_id, lsp_id, loan_product_id, "
                        + "account_number, principal_amount, tenure_months, status, approved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?, 'PENDING_DISBURSEMENT', NOW())",
                UUID.randomUUID(), applicationId, borrowerId, lspId, productId, accountNumber, principalAmount, tenureMonths
        );
    }

    private void insertInstallment(
            UUID loanAccountId,
            int installmentNumber,
            String openingPrincipal,
            String principalDue,
            String interestDue,
            String installmentAmount,
            String closingPrincipal,
            String paidPrincipal,
            String paidInterest,
            String paidAmount,
            String outstandingAmount
    ) {
        jdbcTemplate.update(
                "INSERT INTO loan_repayment_schedule_installment (id, loan_account_id, installment_number, due_date, "
                        + "opening_principal, principal_due, interest_due, installment_amount, closing_principal, "
                        + "status, paid_principal, paid_interest, paid_amount, outstanding_amount) "
                        + "VALUES (?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, "
                        + "'PENDING', ?::numeric, ?::numeric, ?::numeric, ?::numeric)",
                UUID.randomUUID(),
                loanAccountId,
                installmentNumber,
                LocalDate.now().plusDays(30),
                openingPrincipal,
                principalDue,
                interestDue,
                installmentAmount,
                closingPrincipal,
                paidPrincipal,
                paidInterest,
                paidAmount,
                outstandingAmount
        );
    }

    private void insertLoanPaymentTransaction(
            UUID loanAccountId,
            String amount,
            String allocatedAmount,
            String unallocatedAmount
    ) {
        jdbcTemplate.update(
                "INSERT INTO loan_payment_transaction (id, loan_account_id, actor_username, amount, "
                        + "payment_date, reference, channel, status, allocated_amount, unallocated_amount) "
                        + "VALUES (?, ?, 'system', ?::numeric, ?, ?, 'MANUAL', 'POSTED', ?::numeric, ?::numeric)",
                UUID.randomUUID(),
                loanAccountId,
                amount,
                LocalDate.now(),
                "REF-" + UUID.randomUUID().toString().substring(0, 8),
                allocatedAmount,
                unallocatedAmount
        );
    }
}
