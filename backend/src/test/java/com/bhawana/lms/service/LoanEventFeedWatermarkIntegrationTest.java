package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.LoanEvent;
import com.bhawana.lms.repo.LoanEventRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H25: the xid8-watermarked partner feed tolerates long writing transactions — a committed
 * event whose transaction id sits behind an older still-open transaction is withheld (feed
 * lag the oldest-transaction-age rule monitors), never skipped, and feeds in commit order
 * once the long transaction ends.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LoanEventFeedWatermarkIntegrationTest {

    @Autowired
    private LoanEventRepository loanEventRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LoanProductVersionRepository loanProductVersionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void eventCommittedBehindAnOpenTransactionFeedsAfterItEnds() throws Exception {
        Lsp lsp = lspRepository.save(new Lsp("FEED", "Feed Finance", LspStatus.ACTIVE));
        LoanApplication application = seedApplication(lsp);

        CountDownLatch longTransactionPinnedXid = new CountDownLatch(1);
        CountDownLatch releaseLongTransaction = new CountDownLatch(1);
        // An open transaction holding an xid older than the event's: the feed watermark is
        // snapshot-xmin, so rows committed behind it wait instead of leaking out of order.
        Future<?> longTransaction = executor.submit(() ->
                TenantScopedExecution.runAsAdmin(() ->
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            jdbcTemplate.queryForObject("select pg_current_xact_id()", String.class);
                            longTransactionPinnedXid.countDown();
                            awaitLatch(releaseLongTransaction);
                        })
                ));

        assertThat(longTransactionPinnedXid.await(15, TimeUnit.SECONDS)).isTrue();

        UUID eventId = insertLoanEvent(lsp.getId(), application.getId());

        List<LoanEvent> withheld = TenantScopedExecution.callAsAdmin(() ->
                loanEventRepository.findCommittedAfter(lsp.getId(), "0", 0, Set.of(), 50));
        assertThat(withheld.stream().map(LoanEvent::id))
                .as("committed event leaks past the open transaction's xid watermark")
                .doesNotContain(eventId);

        releaseLongTransaction.countDown();
        longTransaction.get(15, TimeUnit.SECONDS);

        List<LoanEvent> fed = TenantScopedExecution.callAsAdmin(() ->
                loanEventRepository.findCommittedAfter(lsp.getId(), "0", 0, Set.of(), 50));
        assertThat(fed.stream().map(LoanEvent::id)).contains(eventId);
    }

    private UUID insertLoanEvent(UUID lspId, UUID applicationId) {
        UUID eventId = UUID.randomUUID();
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.update(
                "INSERT INTO loan_event (id, lsp_id, event_type, aggregate_type, aggregate_id, "
                        + "loan_application_id, payload_json, occurred_at) "
                        + "VALUES (?, ?, 'LOAN_CREATED', 'LOAN_APPLICATION', ?, ?, CAST(? AS jsonb), ?)",
                eventId, lspId, applicationId.toString(), applicationId, "{}", Timestamp.from(Instant.now())
        ));
        return eventId;
    }

    private LoanApplication seedApplication(Lsp lsp) {
        LoanProduct product = loanProductRepository.save(new LoanProduct(
                "FEED-1",
                "Feed Product",
                new BigDecimal("5000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("18.50"),
                new BigDecimal("2.00"),
                6,
                24,
                LoanProductStatus.ACTIVE
        ));
        LoanProductVersion version = loanProductVersionRepository
                .save(LoanProductVersionTestSupport.versionOne(product));
        Borrower borrower = borrowerRepository.save(new Borrower(BorrowerProfile.builder()
                .fullName("Feed Borrower")
                .panNumber("FEEDA1234Z")
                .mobileNumber("9000000001")
                .emailAddress("feed.borrower@example.com")
                .dateOfBirth(LocalDate.of(1992, 3, 10))
                .addressCity("Mumbai")
                .addressState("Maharashtra")
                .employmentStatus("SALARIED")
                .monthlyIncome(new BigDecimal("80000.00"))
                .build()));
        return loanApplicationRepository.save(new LoanApplication(
                borrower,
                lsp,
                product,
                version,
                "FEED-LOAN-001",
                "API",
                new BigDecimal("50000.00"),
                12,
                LoanApplicationStatus.DISBURSED
        ));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the long transaction open.", interrupted);
        }
    }
}
