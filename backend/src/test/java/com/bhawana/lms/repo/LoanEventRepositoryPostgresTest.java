package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.domain.LoanEvent;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.service.LoanEventLog;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LoanEventRepository.class)
class LoanEventRepositoryPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired private LoanEventRepository loanEventRepository;
    @Autowired private LspRepository lspRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSource dataSource;

    @Value("${app.datasource.tenant.username}")
    private String tenantUsername;

    @Value("${app.datasource.tenant.password}")
    private String tenantPassword;

    private UUID firstLspId;
    private UUID secondLspId;
    private UUID firstApplicationId;
    private UUID secondApplicationId;
    private UUID firstLspSecondApplicationId;

    @BeforeEach
    void seedParents() {
        jdbcTemplate.execute("TRUNCATE TABLE loan_event");
        firstLspId = seedLoanApplication("FIRST");
        firstApplicationId = applicationIdFor(firstLspId);
        secondLspId = seedLoanApplication("SECOND");
        secondApplicationId = applicationIdFor(secondLspId);
        // A second loan under the first LSP, so commit ordering can be proven on the query the
        // partner feed actually runs — one LSP's rows, across unrelated loans.
        firstLspSecondApplicationId = seedLoanApplicationFor(firstLspId, "FIRST-B");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateCommittingTransactionIsNotSkippedByCompositePaging() throws Exception {
        UUID firstEventId = UUID.randomUUID();
        UUID firstTransactionSecondEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<LoanEvent>> first = executor.submit(() -> transactionTemplate.execute(status -> {
                LoanEvent firstEvent = append(firstEventId, firstLspId, firstApplicationId, "first");
                LoanEvent secondEvent = append(
                        firstTransactionSecondEventId,
                        firstLspId,
                        firstApplicationId,
                        "first-second"
                );
                firstInserted.countDown();
                await(releaseFirstCommit);
                return List.of(firstEvent, secondEvent);
            }));

            assertThat(firstInserted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<LoanEvent> second = executor.submit(() -> transactionTemplate.execute(status ->
                    append(secondEventId, firstLspId, firstLspSecondApplicationId, "second")
            ));
            LoanEvent secondCommittedFirst = second.get(5, TimeUnit.SECONDS);

            assertThat(loanEventRepository.findCommittedAfter(firstLspId, "0", 0L, Set.of(), 20)).isEmpty();

            releaseFirstCommit.countDown();
            List<LoanEvent> firstCommittedLast = first.get(5, TimeUnit.SECONDS);

            LoanEvent firstPage = loanEventRepository.findCommittedAfter(firstLspId, "0", 0L, Set.of(), 1).getFirst();
            LoanEvent secondPage = loanEventRepository.findCommittedAfter(
                    firstLspId, firstPage.transactionId(), firstPage.position(), Set.of(), 1
            ).getFirst();
            LoanEvent thirdPage = loanEventRepository.findCommittedAfter(
                    firstLspId, secondPage.transactionId(), secondPage.position(), Set.of(), 1
            ).getFirst();

            assertThat(List.of(firstPage.id(), secondPage.id(), thirdPage.id()))
                    .containsExactly(firstEventId, firstTransactionSecondEventId, secondEventId);
            assertThat(new BigInteger(firstCommittedLast.getFirst().transactionId()))
                    .isLessThan(new BigInteger(secondCommittedFirst.transactionId()));
            assertThat(firstPage.transactionId()).isEqualTo(secondPage.transactionId());
            assertThat(firstPage.position()).isLessThan(secondPage.position());
        } finally {
            releaseFirstCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void currentMonthWritesLandInMonthlyPartitionAndRowsAreImmutable() {
        LoanEvent event = transactionTemplate.execute(status ->
                append(UUID.randomUUID(), firstLspId, firstApplicationId, "partitioned")
        );

        String partition = jdbcTemplate.queryForObject(
                "SELECT tableoid::regclass::text FROM loan_event WHERE id = ?",
                String.class,
                event.id()
        );
        assertThat(partition).matches("loan_event_\\d{4}_\\d{2}");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE loan_event SET aggregate_id = 'mutated' WHERE id = ?",
                event.id()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void feedQueryServesOnlyTheOwningLspRows() {
        UUID ownEventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            append(ownEventId, firstLspId, firstApplicationId, "own");
            append(UUID.randomUUID(), secondLspId, secondApplicationId, "other");
        });

        assertThat(loanEventRepository.findCommittedAfter(firstLspId, "0", 0L, Set.of(), 20))
                .singleElement()
                .satisfies(event -> assertThat(event.id()).isEqualTo(ownEventId));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantRoleSeesOnlyItsOwningLspRows() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            append(UUID.randomUUID(), firstLspId, firstApplicationId, "first-tenant");
            append(UUID.randomUUID(), secondLspId, secondApplicationId, "second-tenant");
        });

        try (Connection adminConnection = dataSource.getConnection();
             Connection tenantConnection = DriverManager.getConnection(
                     adminConnection.getMetaData().getURL(), tenantUsername, tenantPassword
             )) {
            tenantConnection.createStatement().execute(
                    "SET app.current_lsp_id = '" + firstLspId + "'"
            );
            try (var result = tenantConnection.createStatement().executeQuery(
                    "SELECT lsp_id FROM loan_event ORDER BY position"
            )) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("lsp_id", UUID.class)).isEqualTo(firstLspId);
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void appendIsUnconditionalAndStopsWritingToTheOutbox() {
        LoanEventLog loanEventLog = new LoanEventLog(
                loanEventRepository,
                new ObjectMapper().findAndRegisterModules()
        );

        transactionTemplate.executeWithoutResult(status -> {
            Lsp lsp = lspRepository.findById(firstLspId).orElseThrow();
            loanEventLog.append(
                    lsp,
                    LoanEventType.LOAN_CREATED,
                    "LOAN_APPLICATION",
                    firstApplicationId.toString(),
                    firstApplicationId,
                    java.util.Map.of("applicationId", firstApplicationId)
            );
        });

        assertThat(loanEventRepository.findCommittedAfter(firstLspId, "0", 0L, Set.of(), 20))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.lspId()).isEqualTo(firstLspId);
                    assertThat(event.eventType()).isEqualTo(LoanEventType.LOAN_CREATED);
                    assertThat(event.payloadJson()).contains(firstApplicationId.toString());
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void appendRejectsCallsOutsideTheLifecycleTransaction() {
        LoanEventLog loanEventLog = new LoanEventLog(
                loanEventRepository,
                new ObjectMapper().findAndRegisterModules()
        );
        Lsp lsp = lspRepository.findById(firstLspId).orElseThrow();

        assertThatThrownBy(() -> loanEventLog.append(
                lsp,
                LoanEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                firstApplicationId.toString(),
                firstApplicationId,
                java.util.Map.of("applicationId", firstApplicationId)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lifecycle transaction");
    }

    private LoanEvent append(UUID eventId, UUID lspId, UUID applicationId, String aggregateId) {
        return loanEventRepository.append(
                eventId,
                lspId,
                LoanEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                aggregateId,
                applicationId,
                "{\"aggregateId\":\"" + aggregateId + "\"}",
                Instant.now(),
                "correlation-" + aggregateId
        );
    }

    private UUID seedLoanApplication(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID lspId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                lspId, "EVENT-" + label + "-" + suffix, "Event " + label
        );
        seedLoanApplicationFor(lspId, label);
        return lspId;
    }

    private UUID seedLoanApplicationFor(UUID lspId, String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID borrowerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID productVersionId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile) VALUES (?, ?, ?, ?)",
                borrowerId, "Borrower " + label, "P" + suffix.substring(0, 7), "9999999999"
        );
        jdbcTemplate.update(
                "INSERT INTO loan_product (id, code, name, min_principal, max_principal, interest_rate, "
                        + "processing_fee_rate, min_tenure_months, max_tenure_months) "
                        + "VALUES (?, ?, ?, 100.00, 100000.00, 10.00, 1.00, 6, 60)",
                productId, "EVENT-PRODUCT-" + suffix, "Event Product"
        );
        jdbcTemplate.update(
                "INSERT INTO loan_product_version (id, loan_product_id, version_number, min_principal, max_principal, "
                        + "interest_rate, processing_fee_rate, min_tenure_months, max_tenure_months, effective_from) "
                        + "VALUES (?, ?, 1, 100.00, 100000.00, 10.00, 1.00, 6, 60, NOW())",
                productVersionId, productId
        );
        jdbcTemplate.update(
                "INSERT INTO loan_application (id, borrower_id, lsp_id, loan_product_id, loan_product_version_id, "
                        + "external_loan_id, source_channel, requested_amount, tenure_months, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'API', 5000.00, 12, 'INITIALIZED')",
                applicationId, borrowerId, lspId, productId, productVersionId, "EVENT-" + label + "-" + suffix
        );
        return applicationId;
    }

    private UUID applicationIdFor(UUID lspId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM loan_application WHERE lsp_id = ? ORDER BY created_at DESC LIMIT 1",
                UUID.class,
                lspId
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for controlled commit order.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while controlling commit order.", exception);
        }
    }

}
