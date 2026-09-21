package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M02: concurrent same-PAN onboarding must converge on one global borrower identity,
 * a failed loan command must leave no durable visibility, and the one recoverable
 * race (a uk_borrower_pan violation left by a writer that bypassed the PAN advisory
 * lock) must resolve through the committed-read reuse path on retry. Everything runs
 * on real tenant transactions against PostgreSQL so RLS and locking are exercised
 * for real.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class BorrowerOnboardingPanRaceIntegrationTest {

    @Autowired
    private LoanApplicationOnboardingService onboardingService;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LoanProductVersionRepository loanProductVersionRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    @MockitoSpyBean
    private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;

    private Lsp lspA;
    private Lsp lspB;
    private LoanProduct product;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanIntegrationTestData();
        TenantScopedExecution.runAsAdmin(() -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            lspA = lspRepository.save(new Lsp("M02-A-" + suffix, "M02 LSP A", LspStatus.ACTIVE));
            lspB = lspRepository.save(new Lsp("M02-B-" + suffix, "M02 LSP B", LspStatus.ACTIVE));
            product = loanProductRepository.save(new LoanProduct(
                    "M02-" + suffix,
                    "M02 product",
                    new BigDecimal("5000.00"),
                    new BigDecimal("250000.00"),
                    new BigDecimal("18.50"),
                    new BigDecimal("2.25"),
                    6,
                    24,
                    LoanProductStatus.ACTIVE
            ));
            loanProductVersionRepository.save(LoanProductVersionTestSupport.versionOne(product));
            loanProductLspMappingRepository.save(new LoanProductLspMapping(product, lspA, true));
            loanProductLspMappingRepository.save(new LoanProductLspMapping(product, lspB, true));
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void concurrentSamePanOnboardingAcrossTwoLspsSharesOneIdentity() throws Exception {
        String pan = TestPanSequence.uniquePan();
        String mobile = uniqueMobile();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<LoanApplication> first = pool.submit(() -> {
                awaitQuietly(gate);
                return onboard(lspA, pan, mobile, "EXT-M02-A-" + pan);
            });
            Future<LoanApplication> second = pool.submit(() -> {
                awaitQuietly(gate);
                return onboard(lspB, pan, mobile, "EXT-M02-B-" + pan);
            });
            gate.countDown();

            LoanApplication applicationA = first.get(120, TimeUnit.SECONDS);
            LoanApplication applicationB = second.get(120, TimeUnit.SECONDS);

            // One global identity, both commands succeeded by establish-then-reuse.
            assertEquals(applicationA.getBorrower().getId(), applicationB.getBorrower().getId());
            assertEquals(lspA.getId(), applicationA.getLsp().getId());
            assertEquals(lspB.getId(), applicationB.getLsp().getId());

            Long borrowerRows = adminQueryCount(
                    "SELECT count(*) FROM borrower WHERE pan = ?", pan);
            assertEquals(1L, borrowerRows);

            UUID borrowerId = applicationA.getBorrower().getId();
            assertEquals(1L, accessCount(borrowerId, lspA.getId()));
            assertEquals(1L, accessCount(borrowerId, lspB.getId()));
            assertEquals(1L, relationshipCount(borrowerId, lspA.getId()));
            assertEquals(1L, relationshipCount(borrowerId, lspB.getId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockBypassingPanWriterIsResolvedByTheBoundedRetry() throws Exception {
        // The advisory lock serializes writers that go through onboarding. A writer
        // that never touches it (a JDBC seeder, a restore) can still win the
        // uk_borrower_pan race between our lookup and our insert; the bounded retry
        // must then resolve through the committed-read reuse path rather than
        // surfacing a 500.
        String pan = TestPanSequence.uniquePan();
        UUID winnerBorrowerId = UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.update(
                                "INSERT INTO borrower (id, full_name, pan, mobile, created_at, updated_at)"
                                        + " VALUES (?, ?, ?, ?, now(), now())",
                                winnerBorrowerId, "Lock Bypassing Writer", pan, uniqueMobile());
                        inserted.countDown();
                        awaitQuietly(release);
                        return null;
                    })));
            assertTrue(inserted.await(30, TimeUnit.SECONDS));

            Future<LoanApplication> contender = pool.submit(() ->
                    onboard(lspA, pan, uniqueMobile(), "EXT-M02-C-" + pan));

            // Prove the contender actually blocks on the winner's in-flight row.
            assertTrue(awaitUngrantedLock(30_000));
            assertFalse(contender.isDone());

            release.countDown();
            LoanApplication application = contender.get(120, TimeUnit.SECONDS);
            holder.get(30, TimeUnit.SECONDS);

            // The retry re-read the committed identity and attached to it.
            assertEquals(winnerBorrowerId, application.getBorrower().getId());
            assertEquals(1L, adminQueryCount("SELECT count(*) FROM borrower WHERE pan = ?", pan));
            assertEquals(1L, accessCount(winnerBorrowerId, lspA.getId()));
            assertEquals(1L, relationshipCount(winnerBorrowerId, lspA.getId()));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedOnboardingLeavesNoBorrowerVisibilityOrRelationship() {
        // The required-field check runs after the borrower is resolved: this failure
        // fires strictly after the visibility grant was staged in the transaction,
        // so a surviving grant would be committed. None may be.
        String pan = TestPanSequence.uniquePan();
        BorrowerProfile incomplete = BorrowerProfile.builder()
                .fullName("M02 Incomplete Borrower")
                .mobileNumber(uniqueMobile())
                .panNumber(pan)
                .addressLine1("1 Test Street")
                .addressCity("Mumbai")
                .addressState("Maharashtra")
                .addressZipcode("400001")
                .monthlyIncome(new BigDecimal("65000.00"))
                .referencePersonName("Reference Person")
                .referencePersonNumber("9000000001")
                .build();

        assertThrows(
                BusinessRuleViolationException.class,
                () -> TenantScopedExecution.callAsTenant(lspA.getId(), () ->
                        onboardingService.createApplication(
                                "lsp.client",
                                command(lspA, incomplete, "EXT-M02-F-" + pan),
                                lspA.getId()))
        );

        assertEquals(0L, adminQueryCount("SELECT count(*) FROM borrower WHERE pan = ?", pan));
        assertEquals(0L, adminQueryCount(
                "SELECT count(*) FROM borrower_lsp_access a"
                        + " JOIN borrower b ON b.id = a.borrower_id WHERE b.pan = ?", pan));
        assertEquals(0L, adminQueryCount(
                "SELECT count(*) FROM borrower_lsp_relationship r"
                        + " JOIN borrower b ON b.id = r.borrower_id WHERE b.pan = ?", pan));
    }

    @Test
    void relationshipInventoryQueriesAreCleanAfterEstablishedOnboarding() {
        String pan = TestPanSequence.uniquePan();
        LoanApplication application = onboard(lspA, pan, uniqueMobile(), "EXT-M02-I-" + pan);
        assertNotNull(application.getId());

        // ADR 0016 §5: every pre-cleanup divergence query must return zero on
        // post-atomic data.
        assertEquals(0L, adminQueryCount(
                "SELECT count(*) FROM borrower_lsp_access a"
                        + " LEFT JOIN borrower_lsp_relationship r"
                        + " ON r.borrower_id = a.borrower_id AND r.lsp_id = a.lsp_id"
                        + " WHERE r.id IS NULL"));
        assertEquals(0L, adminQueryCount(
                "SELECT count(*) FROM borrower_lsp_relationship r"
                        + " LEFT JOIN borrower_lsp_access a"
                        + " ON a.borrower_id = r.borrower_id AND a.lsp_id = r.lsp_id"
                        + " WHERE a.borrower_id IS NULL"));
        assertEquals(0L, adminQueryCount(
                "SELECT count(*) FROM borrower_lsp_access a WHERE NOT EXISTS ("
                        + " SELECT 1 FROM loan_application la"
                        + " WHERE la.borrower_id = a.borrower_id AND la.lsp_id = a.lsp_id)"));
    }

    @Test
    void nonPanIntegrityViolationIsNotRetriedAndRollsBackCleanly() {
        // The duplicate-external-id pre-check races: a contender that loses
        // uk_loan_application_lsp_external at the database is replaying
        // deterministic input, so it must fail on the first attempt — unlike the
        // PAN race, no committed winner improves the answer on retry. The spy
        // raises that violation shape at the intake-audit write, after the
        // borrower insert is already staged, so a propagated failure also proves
        // the borrower and its visibility grant rolled back with the command.
        String pan = TestPanSequence.uniquePan();
        DataIntegrityViolationException nonPanViolation = new DataIntegrityViolationException(
                "duplicate key",
                new ConstraintViolationException(
                        "duplicate key",
                        new SQLException(
                                "duplicate key value violates unique constraint"
                                        + " \"uk_loan_application_lsp_external\"",
                                "23505"),
                        "uk_loan_application_lsp_external"
                )
        );
        Mockito.doThrow(nonPanViolation)
                .when(loanApplicationIntakeAuditRepository)
                .save(ArgumentMatchers.any());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> onboard(lspA, pan, uniqueMobile(), "EXT-M02-N-" + pan)
        );
        Mockito.verify(loanApplicationIntakeAuditRepository, Mockito.times(1))
                .save(ArgumentMatchers.any());
        assertEquals(0L, adminQueryCount("SELECT count(*) FROM borrower WHERE pan = ?", pan));
    }

    private LoanApplication onboard(Lsp lsp, String pan, String mobile, String externalLoanId) {
        return TenantScopedExecution.callAsTenant(lsp.getId(), () ->
                onboardingService.createApplication(
                        "lsp.client",
                        command(lsp, completeProfile(pan, mobile), externalLoanId),
                        lsp.getId()));
    }

    private LoanApplicationOnboardingCommand command(Lsp lsp, BorrowerProfile profile, String externalLoanId) {
        return new LoanApplicationOnboardingCommand(
                lsp.getId(),
                product.getId(),
                null,
                externalLoanId,
                "API",
                new BigDecimal("45000.00"),
                new BigDecimal("18.50"),
                12,
                profile
        );
    }

    private static BorrowerProfile completeProfile(String pan, String mobile) {
        return BorrowerProfile.builder()
                .fullName("M02 Race Borrower")
                .emailAddress("m02-" + pan.toLowerCase() + "@example.com")
                .mobileNumber(mobile)
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .aadharNumber("123456789012")
                .panNumber(pan)
                .addressLine1("1 Test Street")
                .addressCity("Mumbai")
                .addressState("Maharashtra")
                .addressZipcode("400001")
                .monthlyIncome(new BigDecimal("65000.00"))
                .referencePersonName("Reference Person")
                .referencePersonNumber("9000000001")
                .build();
    }

    private long adminQueryCount(String sql, Object... args) {
        Long count = TenantScopedExecution.callAsAdmin(() ->
                jdbcTemplate.queryForObject(sql, Long.class, args));
        return count == null ? -1 : count;
    }

    private long accessCount(UUID borrowerId, UUID lspId) {
        return adminQueryCount(
                "SELECT count(*) FROM borrower_lsp_access WHERE borrower_id = ? AND lsp_id = ?",
                borrowerId, lspId);
    }

    private long relationshipCount(UUID borrowerId, UUID lspId) {
        return adminQueryCount(
                "SELECT count(*) FROM borrower_lsp_relationship WHERE borrower_id = ? AND lsp_id = ?",
                borrowerId, lspId);
    }

    /**
     * Polls pg_locks for a lock another session is waiting on — proof that the
     * contender is genuinely blocked on the holder's uncommitted insert rather
     * than merely scheduled slowly.
     */
    private boolean awaitUngrantedLock(long deadlineMillis) {
        long deadline = System.currentTimeMillis() + deadlineMillis;
        while (System.currentTimeMillis() < deadline) {
            long waiting = adminQueryCount(
                    "SELECT count(*) FROM pg_locks WHERE NOT granted");
            if (waiting > 0) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting latch", e);
        }
    }

    private static String uniqueMobile() {
        return String.format("9%09d", Math.abs(UUID.randomUUID().hashCode() % 1_000_000_000));
    }
}
