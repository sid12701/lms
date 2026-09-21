package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

/**
 * M18: direct-SQL evidence that V139's checks and composite foreign keys enforce the
 * financial and ownership invariants at the database boundary — a hand edit, a restore,
 * or a future service path that bypasses {@code LoanServicingSupportService} cannot
 * produce an installment whose components don't reconcile, a payment aimed at another
 * account's installment or quote, or an account that disagrees with its application's
 * identity. The per-constraint inventory queries from ADR 0014 are also exercised and
 * must return zero on the fixture data.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LoanComponentOwnershipInvariantIntegrationTest {

    @Autowired private LoanRepaymentScheduleInstallmentRepository installmentRepository;
    @Autowired private LoanForeclosureQuoteRepository foreclosureQuoteRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private BorrowerRepository borrowerRepository;
    @Autowired private LspRepository lspRepository;
    @Autowired private LoanProductRepository loanProductRepository;
    @Autowired private LoanProductVersionRepository loanProductVersionRepository;
    @Autowired private LoanProductLspMappingRepository loanProductLspMappingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    private Lsp lsp;
    private LoanProduct productA;
    private LoanProduct productB;
    private LoanProductVersion versionA;
    private LoanProductVersion versionB;
    private Borrower borrower;
    private Borrower otherBorrower;
    private LoanApplication applicationA;
    private LoanApplication applicationB;
    private LoanAccount accountA;
    private LoanAccount accountB;
    private LoanRepaymentScheduleInstallment installmentA;
    private LoanRepaymentScheduleInstallment installmentB;
    private LoanForeclosureQuote quoteB;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanIntegrationTestData();
        TenantScopedExecution.runAsAdmin(() -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            lsp = lspRepository.save(new Lsp("M18-" + suffix, "M18 LSP", LspStatus.ACTIVE));
            productA = loanProductRepository.save(new LoanProduct(
                    "M18A-" + suffix, "M18 Product A",
                    new BigDecimal("5000.00"), new BigDecimal("250000.00"),
                    new BigDecimal("18.50"), new BigDecimal("2.25"), 6, 24,
                    LoanProductStatus.ACTIVE));
            productB = loanProductRepository.save(new LoanProduct(
                    "M18B-" + suffix, "M18 Product B",
                    new BigDecimal("5000.00"), new BigDecimal("250000.00"),
                    new BigDecimal("15.00"), new BigDecimal("1.00"), 6, 24,
                    LoanProductStatus.ACTIVE));
            versionA = loanProductVersionRepository.save(LoanProductVersionTestSupport.versionOne(productA));
            versionB = loanProductVersionRepository.save(LoanProductVersionTestSupport.versionOne(productB));
            loanProductLspMappingRepository.save(new LoanProductLspMapping(productA, lsp, true));
            loanProductLspMappingRepository.save(new LoanProductLspMapping(productB, lsp, true));

            borrower = borrowerRepository.save(new Borrower(BorrowerProfile.minimal(
                    "M18 Borrower", TestPanSequence.uniquePan(), "9000000001", "m18a@example.com")));
            otherBorrower = borrowerRepository.save(new Borrower(BorrowerProfile.minimal(
                    "M18 Other Borrower", TestPanSequence.uniquePan(), "9000000002", "m18b@example.com")));

            applicationA = loanApplicationRepository.save(new LoanApplication(
                    borrower, lsp, productA, versionA,
                    "EXT-M18-A-" + suffix, "API",
                    new BigDecimal("45000.00"), 12, LoanApplicationStatus.DISBURSED));
            applicationB = loanApplicationRepository.save(new LoanApplication(
                    otherBorrower, lsp, productA, versionA,
                    "EXT-M18-B-" + suffix, "API",
                    new BigDecimal("30000.00"), 12, LoanApplicationStatus.DISBURSED));

            accountA = loanAccountRepository.save(new LoanAccount(
                    applicationA, borrower, lsp, productA, versionA,
                    "ACC-M18-A-" + suffix, new BigDecimal("45000.00"), 12,
                    LoanAccountStatus.DISBURSED, Instant.now()));
            accountB = loanAccountRepository.save(new LoanAccount(
                    applicationB, otherBorrower, lsp, productA, versionA,
                    "ACC-M18-B-" + suffix, new BigDecimal("30000.00"), 12,
                    LoanAccountStatus.DISBURSED, Instant.now()));

            installmentA = installmentRepository.save(new LoanRepaymentScheduleInstallment(
                    accountA, 1, LocalDate.now().plusMonths(1),
                    new BigDecimal("45000.00"), new BigDecimal("3306.25"),
                    new BigDecimal("693.75"), new BigDecimal("4000.00"),
                    new BigDecimal("41693.75")));
            installmentB = installmentRepository.save(new LoanRepaymentScheduleInstallment(
                    accountB, 1, LocalDate.now().plusMonths(1),
                    new BigDecimal("30000.00"), new BigDecimal("3500.00"),
                    new BigDecimal("500.00"), new BigDecimal("4000.00"),
                    new BigDecimal("26500.00")));

            quoteB = foreclosureQuoteRepository.save(new LoanForeclosureQuote(
                    accountB, 1, "ops.user", LocalDate.now(),
                    new BigDecimal("26500.00"), new BigDecimal("500.00"),
                    new BigDecimal("27000.00")));
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void installmentAmountMustEqualPrincipalPlusInterest() {
        DataIntegrityViolationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_repayment_schedule_installment ("
                                + "id, loan_account_id, installment_number, due_date, opening_principal,"
                                + " principal_due, interest_due, installment_amount, closing_principal,"
                                + " status, paid_principal, paid_interest, paid_amount, outstanding_amount)"
                                + " VALUES (gen_random_uuid(), ?, 90, CURRENT_DATE + 30, 45000.00,"
                                + " 3000.00, 500.00, 9999.00, 42000.00,"
                                + " 'PENDING', 0, 0, 0, 9999.00)",
                        accountA.getId()));
        assertTrue(mentionsConstraint(failure, "chk_installment_amount_components"));
    }

    @Test
    void closingPrincipalMustEqualOpeningMinusPrincipalDue() {
        DataIntegrityViolationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_repayment_schedule_installment ("
                                + "id, loan_account_id, installment_number, due_date, opening_principal,"
                                + " principal_due, interest_due, installment_amount, closing_principal,"
                                + " status, paid_principal, paid_interest, paid_amount, outstanding_amount)"
                                + " VALUES (gen_random_uuid(), ?, 91, CURRENT_DATE + 30, 45000.00,"
                                + " 3000.00, 500.00, 3500.00, 9999.00,"
                                + " 'PENDING', 0, 0, 0, 3500.00)",
                        accountA.getId()));
        assertTrue(mentionsConstraint(failure, "chk_installment_principal_reconcile"));
    }

    @Test
    void paidComponentsCannotExceedTheirDueBounds() {
        // Each row below satisfies every V65 check (component nonnegativity,
        // paid_amount = paid_principal + paid_interest, paid + outstanding =
        // installment_amount), so the only constraint that can fire is the new
        // per-component bound.
        DataIntegrityViolationException principalOverflow = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_repayment_schedule_installment ("
                                + "id, loan_account_id, installment_number, due_date, opening_principal,"
                                + " principal_due, interest_due, installment_amount, closing_principal,"
                                + " status, paid_principal, paid_interest, paid_amount, outstanding_amount)"
                                + " VALUES (gen_random_uuid(), ?, 92, CURRENT_DATE + 30, 45000.00,"
                                + " 3000.00, 500.00, 3500.00, 42000.00,"
                                + " 'PAID', 3100.00, 400.00, 3500.00, 0.00)",
                        accountA.getId()));
        assertTrue(mentionsConstraint(principalOverflow, "chk_installment_paid_principal_bounded"));

        DataIntegrityViolationException interestOverflow = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_repayment_schedule_installment ("
                                + "id, loan_account_id, installment_number, due_date, opening_principal,"
                                + " principal_due, interest_due, installment_amount, closing_principal,"
                                + " status, paid_principal, paid_interest, paid_amount, outstanding_amount)"
                                + " VALUES (gen_random_uuid(), ?, 93, CURRENT_DATE + 30, 45000.00,"
                                + " 3000.00, 500.00, 3500.00, 42000.00,"
                                + " 'PARTIALLY_PAID', 2000.00, 600.00, 2600.00, 900.00)",
                        accountA.getId()));
        assertTrue(mentionsConstraint(interestOverflow, "chk_installment_paid_interest_bounded"));
    }

    @Test
    void updatePathCannotBreakComponentArithmetic() {
        // The constraints bind UPDATEs too, not just inserts.
        DataIntegrityViolationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "UPDATE loan_repayment_schedule_installment"
                                + " SET paid_interest = interest_due + 1.00,"
                                + " paid_amount = paid_amount + 1.00,"
                                + " outstanding_amount = outstanding_amount - 1.00"
                                + " WHERE id = ?",
                        installmentA.getId()));
        assertTrue(mentionsConstraint(failure, "chk_installment_paid_interest_bounded"));

        // Confirm nothing was written.
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_repayment_schedule_installment"
                        + " WHERE id = ? AND paid_interest > interest_due",
                installmentA.getId()));
    }

    @Test
    void legitimateInstallmentRowsPass() {
        // Exact component equality — including the rounding-adjusted final
        // installment shape the generator emits — must always succeed.
        runAsAdminUpdate(
                "INSERT INTO loan_repayment_schedule_installment ("
                        + "id, loan_account_id, installment_number, due_date, opening_principal,"
                        + " principal_due, interest_due, installment_amount, closing_principal,"
                        + " status, paid_principal, paid_interest, paid_amount, outstanding_amount)"
                        + " VALUES (gen_random_uuid(), ?, 94, CURRENT_DATE + 30, 41693.75,"
                        + " 41693.75, 623.15, 42316.90, 0.00,"
                        + " 'PENDING', 0, 0, 0, 42316.90)",
                accountA.getId());
        assertEquals(1L, adminCount(
                "SELECT count(*) FROM loan_repayment_schedule_installment"
                        + " WHERE loan_account_id = ? AND installment_number = 94",
                accountA.getId()));
    }

    @Test
    void paymentCannotTargetAnotherAccountsInstallment() {
        DataIntegrityViolationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_payment_transaction ("
                                + "id, loan_account_id, repayment_installment_id, actor_username, amount,"
                                + " payment_date, channel, status, allocated_amount, unallocated_amount)"
                                + " VALUES (gen_random_uuid(), ?, ?, 'sql.writer', 4000.00,"
                                + " CURRENT_DATE, 'NEFT', 'RECEIVED', 4000.00, 0)",
                        accountA.getId(), installmentB.getId()));
        assertTrue(mentionsConstraint(failure, "fk_payment_installment_same_account"));
    }

    @Test
    void paymentCannotTargetAnotherAccountsForeclosureQuote() {
        DataIntegrityViolationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_payment_transaction ("
                                + "id, loan_account_id, foreclosure_quote_id, actor_username, amount,"
                                + " payment_date, channel, status, allocated_amount, unallocated_amount)"
                                + " VALUES (gen_random_uuid(), ?, ?, 'sql.writer', 27000.00,"
                                + " CURRENT_DATE, 'NEFT', 'RECEIVED', 27000.00, 0)",
                        accountA.getId(), quoteB.getId()));
        assertTrue(mentionsConstraint(failure, "fk_payment_foreclosure_quote_same_account"));
    }

    @Test
    void untargetedReceiptsAndSameAccountTargetsStillPass() {
        runAsAdminUpdate(
                "INSERT INTO loan_payment_transaction ("
                        + "id, loan_account_id, actor_username, amount, payment_date,"
                        + " channel, status, allocated_amount, unallocated_amount)"
                        + " VALUES (gen_random_uuid(), ?, 'sql.writer', 4000.00, CURRENT_DATE,"
                        + " 'NEFT', 'RECEIVED', 0, 4000.00)",
                accountA.getId());
        runAsAdminUpdate(
                "INSERT INTO loan_payment_transaction ("
                        + "id, loan_account_id, repayment_installment_id, actor_username, amount,"
                        + " payment_date, channel, status, allocated_amount, unallocated_amount)"
                        + " VALUES (gen_random_uuid(), ?, ?, 'sql.writer', 4000.00, CURRENT_DATE,"
                        + " 'NEFT', 'RECEIVED', 4000.00, 0)",
                accountA.getId(), installmentA.getId());
        assertEquals(2L, adminCount(
                "SELECT count(*) FROM loan_payment_transaction WHERE loan_account_id = ?",
                accountA.getId()));
    }

    @Test
    void applicationCannotPinAnotherProductsVersion() {
        DataIntegrityViolationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_application ("
                                + "id, borrower_id, lsp_id, loan_product_id, loan_product_version_id,"
                                + " external_loan_id, source_channel, requested_amount, tenure_months,"
                                + " status)"
                                + " VALUES (gen_random_uuid(), ?, ?, ?, ?, 'EXT-M18-BADVERSION',"
                                + " 'API', 45000.00, 12, 'INITIALIZED')",
                        borrower.getId(), lsp.getId(), productA.getId(), versionB.getId()));
        assertTrue(mentionsConstraint(failure, "fk_application_version_same_product"));
    }

    @Test
    void accountMustMatchItsApplicationsIdentity() {
        // Application C belongs to `borrower` on product A / version A. An account
        // pointing at it but carrying `otherBorrower` violates the composite
        // identity FK while satisfying the plain application FK — the composite
        // shape is what makes the divergence a database error at all.
        LoanApplication appC = TenantScopedExecution.callAsAdmin(() -> loanApplicationRepository.save(
                new LoanApplication(borrower, lsp, productA, versionA,
                        "EXT-M18-C-" + UUID.randomUUID().toString().substring(0, 6), "API",
                        new BigDecimal("20000.00"), 12, LoanApplicationStatus.DISBURSED)));
        DataIntegrityViolationException identityMismatch = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_account ("
                                + "id, loan_application_id, borrower_id, lsp_id, loan_product_id,"
                                + " loan_product_version_id, account_number, principal_amount,"
                                + " tenure_months, status, approved_at)"
                                + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?,"
                                + " 'ACC-M18-BADAPP', 20000.00, 12, 'DISBURSED', now())",
                        appC.getId(), otherBorrower.getId(), lsp.getId(),
                        productA.getId(), versionA.getId()));
        assertTrue(mentionsConstraint(identityMismatch, "fk_account_matches_application"));

        // Product mismatch against the same application: still an identity break.
        DataIntegrityViolationException productMismatch = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_account ("
                                + "id, loan_application_id, borrower_id, lsp_id, loan_product_id,"
                                + " loan_product_version_id, account_number, principal_amount,"
                                + " tenure_months, status, approved_at)"
                                + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?,"
                                + " 'ACC-M18-BADPROD', 20000.00, 12, 'DISBURSED', now())",
                        appC.getId(), borrower.getId(), lsp.getId(),
                        productB.getId(), versionB.getId()));
        assertTrue(mentionsConstraint(productMismatch, "fk_account_matches_application"));
    }

    @Test
    void negativeForeclosureQuoteComponentsAreRejected() {
        DataIntegrityViolationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> runAsAdminUpdate(
                        "INSERT INTO loan_foreclosure_quote ("
                                + "id, loan_account_id, version, requested_by_username, effective_date,"
                                + " outstanding_principal, outstanding_interest, settlement_amount,"
                                + " status)"
                                + " VALUES (gen_random_uuid(), ?, 9, 'sql.writer', CURRENT_DATE,"
                                + " -1.00, 0.00, 0.00, 'ACTIVE')",
                        accountA.getId()));
        assertTrue(mentionsConstraint(failure));
    }

    @Test
    void inventoryQueriesReturnZeroOnCleanFixtures() {
        // ADR 0014 §8 — every pre-migration inventory query must return zero on
        // data the migration is expected to validate.
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_repayment_schedule_installment"
                        + " WHERE installment_amount <> principal_due + interest_due"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_repayment_schedule_installment"
                        + " WHERE closing_principal <> opening_principal - principal_due"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_repayment_schedule_installment"
                        + " WHERE paid_principal > principal_due OR paid_interest > interest_due"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_foreclosure_quote"
                        + " WHERE outstanding_principal < 0 OR outstanding_interest < 0"
                        + " OR settlement_amount < 0"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_payment_transaction p"
                        + " JOIN loan_repayment_schedule_installment i"
                        + " ON i.id = p.repayment_installment_id"
                        + " WHERE i.loan_account_id <> p.loan_account_id"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_payment_transaction p"
                        + " JOIN loan_foreclosure_quote q ON q.id = p.foreclosure_quote_id"
                        + " WHERE q.loan_account_id <> p.loan_account_id"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_application a"
                        + " JOIN loan_product_version v ON v.id = a.loan_product_version_id"
                        + " WHERE v.loan_product_id <> a.loan_product_id"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_account ac"
                        + " JOIN loan_product_version v ON v.id = ac.loan_product_version_id"
                        + " WHERE v.loan_product_id <> ac.loan_product_id"));
        assertEquals(0L, adminCount(
                "SELECT count(*) FROM loan_account ac"
                        + " JOIN loan_application a ON a.id = ac.loan_application_id"
                        + " WHERE a.borrower_id <> ac.borrower_id OR a.lsp_id <> ac.lsp_id"
                        + " OR a.loan_product_id <> ac.loan_product_id"
                        + " OR a.loan_product_version_id <> ac.loan_product_version_id"));
    }

    private void runAsAdminUpdate(String sql, Object... args) {
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.update(sql, args));
    }

    private long adminCount(String sql, Object... args) {
        Long count = TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.queryForObject(sql, Long.class, args));
        return count == null ? -1 : count;
    }

    private static final String[] V139_CONSTRAINT_NAMES = {
            "chk_installment_amount_components",
            "chk_installment_principal_reconcile",
            "chk_installment_paid_principal_bounded",
            "chk_installment_paid_interest_bounded",
            "chk_foreclosure_quote_",
            "fk_payment_installment_same_account",
            "fk_payment_foreclosure_quote_same_account",
            "fk_application_version_same_product",
            "fk_account_version_same_product",
            "fk_account_matches_application"
    };

    private static boolean mentionsConstraint(DataIntegrityViolationException failure, String... names) {
        StringBuilder haystack = new StringBuilder(String.valueOf(failure.getMessage()));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            haystack.append(' ').append(String.valueOf(cause.getMessage()));
        }
        String text = haystack.toString();
        String[] expected = names.length == 0 ? V139_CONSTRAINT_NAMES : names;
        for (String name : expected) {
            if (text.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
