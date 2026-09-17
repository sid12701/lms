package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanRepaymentCommandService;
import com.bhawana.lms.service.LoanServicingSupportService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Audit H06 (repayment and settlement must serialize changes to the whole loan) and H07 (the final
 * successful payment must stay replayable after closure).
 *
 * <p>Concurrency is exercised through real HTTP requests on separate threads, each with its own
 * connection and transaction, against the integration Postgres. The threads are brought together
 * at the closure decision — the read whose staleness causes H06 — by a barrier installed on
 * {@link LoanServicingSupportService#synchronizeLoanAccountClosureState}; the barrier only
 * schedules, it never changes what the code does. Its wait is bounded because the fix is precisely that the second
 * writer can no longer be in that section while the first one holds the loan lock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LoanRepaymentSettlementConcurrencyIntegrationTest {

    private static final int INSTALLMENT_COUNT = 12;
    private static final long RENDEZVOUS_TIMEOUT_MILLIS = 3_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private DisbursementIntentWorkflowService disbursementIntentWorkflowService;

    @Autowired
    private LoanDisbursementCommandService loanDisbursementCommandService;

    @Autowired
    private LoanRepaymentCommandService loanRepaymentCommandService;

    @MockitoSpyBean
    private LoanServicingSupportService loanServicingSupportService;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        Mockito.reset(loanServicingSupportService);
    }

    @Test
    void concurrentFinalInstallmentPaymentsCloseTheLoanExactlyOnce() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("H06-CLOSE");
        LocalDate postedAt = LocalDate.now().minusDays(1);
        for (int installmentNumber = 1; installmentNumber <= INSTALLMENT_COUNT - 2; installmentNumber++) {
            payInstallmentViaOps(fixture, installmentNumber, postedAt, UUID.randomUUID().toString());
        }
        assertEquals("UNDER_REPAYMENT", applicationStatus(fixture.applicationId()));

        rendezvousBeforeClosureDecision(2);
        List<PaymentAttempt> attempts = raceOpsPayments(
                fixture,
                postedAt,
                INSTALLMENT_COUNT - 1,
                INSTALLMENT_COUNT
        );

        assertEquals(List.of(200, 200), attempts.stream().map(PaymentAttempt::status).toList());
        assertEquals(0, BigDecimal.ZERO.compareTo(outstandingTotal(fixture.applicationId())));
        assertEquals(INSTALLMENT_COUNT, paymentRowCount(fixture.applicationId()));
        assertEquals("CLOSED", applicationStatus(fixture.applicationId()));
        assertEquals("CLOSED", loanAccountStatus(fixture.applicationId()));
        assertEquals("FULLY_REPAID", loanAccountClosureReason(fixture.applicationId()));
        assertEquals(1, eventCount(fixture.applicationId(), "LOAN_FULLY_REPAID"));
        assertEquals(INSTALLMENT_COUNT, eventCount(fixture.applicationId(), "LOAN_REPAYMENT_RECORDED"));
    }

    /**
     * The losing contender of an application version clash has no committed receipt under its own
     * key, so recovery may not assume a winner exists. Both payments are safe and must both land.
     */
    @Test
    void concurrentFirstPaymentsBothSettleWithoutUnexplainedFailure() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("H06-CONTEND");
        LocalDate postedAt = LocalDate.now().minusDays(1);
        assertEquals("DISBURSED", applicationStatus(fixture.applicationId()));

        rendezvousBeforeClosureDecision(2);
        List<PaymentAttempt> attempts = raceOpsPayments(fixture, postedAt, 1, 2);

        assertEquals(List.of(200, 200), attempts.stream().map(PaymentAttempt::status).toList());
        assertEquals(2, paymentRowCount(fixture.applicationId()));
        assertEquals("UNDER_REPAYMENT", applicationStatus(fixture.applicationId()));
        assertEquals(2, eventCount(fixture.applicationId(), "LOAN_REPAYMENT_RECORDED"));
    }

    @Test
    void finalPaymentReplaysAfterClosureThroughCommandAndBothHttpPaths() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("H07-REPLAY");
        LocalDate postedAt = LocalDate.now().minusDays(1);
        for (int installmentNumber = 1; installmentNumber < INSTALLMENT_COUNT; installmentNumber++) {
            payInstallmentViaOps(fixture, installmentNumber, postedAt, UUID.randomUUID().toString());
        }

        String finalInstallmentId = fixture.installmentId(INSTALLMENT_COUNT);
        BigDecimal finalDueAmount = fixture.dueAmount(INSTALLMENT_COUNT);
        String idempotencyKey = UUID.randomUUID().toString();
        MvcResult finalPayment = postOpsPayment(
                fixture.applicationId(),
                finalInstallmentId,
                finalDueAmount,
                "PAY-FINAL",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk()).andReturn();
        String receiptId = jsonField(finalPayment, "id");

        assertEquals("CLOSED", applicationStatus(fixture.applicationId()));
        long auditEventsAfterClosure = auditEventCount(fixture.applicationId());

        MvcResult opsReplay = postOpsPayment(
                fixture.applicationId(),
                finalInstallmentId,
                finalDueAmount,
                "PAY-FINAL",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk()).andReturn();
        assertEquals(receiptId, jsonField(opsReplay, "id"));

        MvcResult lspReplay = postLspPayment(
                fixture,
                finalInstallmentId,
                finalDueAmount,
                "PAY-FINAL",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk()).andReturn();
        assertEquals(receiptId, jsonField(lspReplay, "id"));

        UUID commandReplayId = TenantScopedExecution.callAsAdmin(() -> loanRepaymentCommandService
                .recordPaymentTransactionWithRecovery(
                        UUID.fromString(fixture.applicationId()),
                        "ops.admin",
                        idempotencyKey,
                        UUID.fromString(finalInstallmentId),
                        finalDueAmount,
                        postedAt,
                        "PAY-FINAL",
                        LoanPaymentChannel.UPI
                ).getId());
        assertEquals(receiptId, commandReplayId.toString());

        assertEquals(INSTALLMENT_COUNT, paymentRowCount(fixture.applicationId()));
        assertEquals(INSTALLMENT_COUNT, eventCount(fixture.applicationId(), "LOAN_REPAYMENT_RECORDED"));
        assertEquals(1, eventCount(fixture.applicationId(), "LOAN_FULLY_REPAID"));
        assertEquals(auditEventsAfterClosure, auditEventCount(fixture.applicationId()));

        // The same key with any other payload stays a conflict, closure notwithstanding.
        expectIdempotencyConflict(postOpsPayment(
                fixture.applicationId(),
                finalInstallmentId,
                new BigDecimal("5000.00"),
                "PAY-FINAL",
                idempotencyKey,
                postedAt
        ));
        expectIdempotencyConflict(postOpsPayment(
                fixture.applicationId(),
                finalInstallmentId,
                finalDueAmount,
                "PAY-FINAL",
                idempotencyKey,
                postedAt.minusDays(1)
        ));
        expectIdempotencyConflict(postOpsPayment(
                fixture.applicationId(),
                fixture.installmentId(INSTALLMENT_COUNT - 1),
                fixture.dueAmount(INSTALLMENT_COUNT - 1),
                "PAY-FINAL",
                idempotencyKey,
                postedAt
        ));
        assertEquals(INSTALLMENT_COUNT, paymentRowCount(fixture.applicationId()));
    }

    @Test
    void reusedKeyOnAnotherLoanConflictsAndAForeignTenantCannotDiscoverTheReceipt() throws Exception {
        DisbursedLoanFixture owned = seedDisbursedLoan("H07-OWNED");
        DisbursedLoanFixture other = seedDisbursedLoan("H07-OTHER");
        assertNotEquals(owned.lspId(), other.lspId());
        LocalDate postedAt = LocalDate.now().minusDays(1);

        String idempotencyKey = UUID.randomUUID().toString();
        postOpsPayment(
                owned.applicationId(),
                owned.installmentId(1),
                owned.dueAmount(1),
                "PAY-SCOPE",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk());

        expectIdempotencyConflict(postOpsPayment(
                other.applicationId(),
                other.installmentId(1),
                other.dueAmount(1),
                "PAY-SCOPE",
                idempotencyKey,
                postedAt
        ));

        // The other tenant's own credentials cannot reach the loan the receipt belongs to, so the
        // ownership failure happens before the key is ever looked up.
        postLspPayment(
                other.accessToken(),
                owned.loanAccountId(),
                owned.installmentId(2),
                owned.dueAmount(2),
                "PAY-SCOPE",
                idempotencyKey,
                postedAt
        ).andExpect(status().isNotFound());

        assertEquals(1, paymentRowCount(owned.applicationId()));
        assertEquals(0, paymentRowCount(other.applicationId()));
    }

    /**
     * Any other command that writes the loan application bumps its version. The payment used to
     * lose that clash, roll its own receipt back, and then hand recovery an idempotency key no
     * transaction ever committed — an unexplained 500. Under the loan lock the competing write
     * waits instead, and a clash that still happens costs a retry, not a receipt.
     */
    @Test
    void concurrentApplicationWriteCostsARetryRatherThanAReceiptThatWasNeverCommitted() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("H06-NOWINNER");
        LocalDate postedAt = LocalDate.now().minusDays(1);
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        parkFirstClosureDecision(parked, resume);

        PaymentAttempt attempt;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<PaymentAttempt> payment = executor.submit(opsPaymentTask(fixture, 1, postedAt));
            assertTrue(parked.await(30, TimeUnit.SECONDS), "payment never reached the closure decision");
            bumpApplicationVersion(fixture.applicationId());
            resume.countDown();
            attempt = payment.get(60, TimeUnit.SECONDS);
        }

        assertEquals(200, attempt.status(), attempt.body());
        assertEquals(1, paymentRowCount(fixture.applicationId()));
        assertEquals("UNDER_REPAYMENT", applicationStatus(fixture.applicationId()));
        assertEquals(1, eventCount(fixture.applicationId(), "LOAN_REPAYMENT_RECORDED"));
    }

    /**
     * Installs a rendezvous immediately before the closure decision, so both payment transactions
     * have flushed their own installment and are open at the moment each one reads the whole
     * schedule. Waiting is bounded and a timeout is expected once the loan lock serializes the
     * writers — that outcome is the fix working, not a failure.
     */
    private void rendezvousBeforeClosureDecision(int parties) {
        CyclicBarrier barrier = new CyclicBarrier(parties);
        Mockito.doAnswer(invocation -> {
            try {
                barrier.await(RENDEZVOUS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | BrokenBarrierException expectedWhenSerialized) {
                barrier.reset();
            }
            return invocation.callRealMethod();
        }).when(loanServicingSupportService).synchronizeLoanAccountClosureState(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    /**
     * Parks the first payment transaction just before its closure decision, with its own receipt
     * already flushed but nothing committed, and lets the caller act while it is held there. The
     * wait is bounded because under the loan lock the competing writer is the one that waits.
     */
    private void parkFirstClosureDecision(CountDownLatch parked, CountDownLatch resume) {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        Mockito.doAnswer(invocation -> {
            if (firstCall.compareAndSet(true, false)) {
                parked.countDown();
                resume.await(RENDEZVOUS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }
            return invocation.callRealMethod();
        }).when(loanServicingSupportService).synchronizeLoanAccountClosureState(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    /**
     * Stands in for any other command writing the loan application: the row version is what the
     * payment's own application update is checked against.
     */
    private void bumpApplicationVersion(String applicationId) {
        assertEquals(
                1,
                jdbcTemplate.update(
                        "update loan_application set entity_version = entity_version + 1 where id = ?",
                        UUID.fromString(applicationId)
                )
        );
    }

    private List<PaymentAttempt> raceOpsPayments(
            DisbursedLoanFixture fixture,
            LocalDate postedAt,
            int firstInstallmentNumber,
            int secondInstallmentNumber
    ) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<PaymentAttempt>> tasks = List.of(
                    opsPaymentTask(fixture, firstInstallmentNumber, postedAt),
                    opsPaymentTask(fixture, secondInstallmentNumber, postedAt)
            );
            List<PaymentAttempt> attempts = new ArrayList<>();
            for (Future<PaymentAttempt> future : executor.invokeAll(tasks)) {
                attempts.add(future.get());
            }
            return attempts;
        }
    }

    private Callable<PaymentAttempt> opsPaymentTask(
            DisbursedLoanFixture fixture,
            int installmentNumber,
            LocalDate postedAt
    ) {
        String idempotencyKey = UUID.randomUUID().toString();
        return () -> TenantScopedExecution.callAsAdmin(() -> {
            try {
                MvcResult result = postOpsPayment(
                        fixture.applicationId(),
                        fixture.installmentId(installmentNumber),
                        fixture.dueAmount(installmentNumber),
                        "PAY-RACE-" + installmentNumber,
                        idempotencyKey,
                        postedAt
                ).andReturn();
                return new PaymentAttempt(
                        result.getResponse().getStatus(),
                        result.getResponse().getContentAsString()
                );
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private void payInstallmentViaOps(
            DisbursedLoanFixture fixture,
            int installmentNumber,
            LocalDate postedAt,
            String idempotencyKey
    ) throws Exception {
        postOpsPayment(
                fixture.applicationId(),
                fixture.installmentId(installmentNumber),
                fixture.dueAmount(installmentNumber),
                "PAY-SEQ-" + installmentNumber,
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk());
    }

    private void expectIdempotencyConflict(ResultActions resultActions) throws Exception {
        resultActions
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }

    private ResultActions postOpsPayment(
            String applicationId,
            String installmentId,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            LocalDate postedAt
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                .with(systemAdmin())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentBody(installmentId, amount, reference, postedAt)));
    }

    private ResultActions postLspPayment(
            DisbursedLoanFixture fixture,
            String installmentId,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            LocalDate postedAt
    ) throws Exception {
        return postLspPayment(
                fixture.accessToken(),
                fixture.loanAccountId(),
                installmentId,
                amount,
                reference,
                idempotencyKey,
                postedAt
        );
    }

    private ResultActions postLspPayment(
            String accessToken,
            String loanAccountId,
            String installmentId,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            LocalDate postedAt
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/lsp/loans/{loanId}/payments", loanAccountId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentBody(installmentId, amount, reference, postedAt)));
    }

    private String paymentBody(
            String installmentId,
            BigDecimal amount,
            String reference,
            LocalDate postedAt
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetInstallmentId", installmentId);
        body.put("amount", amount);
        body.put("postedAt", postedAt.toString());
        body.put("channel", "UPI");
        body.put("reference", reference);
        return objectMapper.writeValueAsString(body);
    }

    private String jsonField(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get(field).asText();
    }

    private String applicationStatus(String applicationId) {
        return jdbcTemplate.queryForObject(
                "select status from loan_application where id = ?",
                String.class,
                UUID.fromString(applicationId)
        );
    }

    private String loanAccountStatus(String applicationId) {
        return jdbcTemplate.queryForObject(
                "select status from loan_account where loan_application_id = ?",
                String.class,
                UUID.fromString(applicationId)
        );
    }

    private String loanAccountClosureReason(String applicationId) {
        return jdbcTemplate.queryForObject(
                "select closure_reason from loan_account where loan_application_id = ?",
                String.class,
                UUID.fromString(applicationId)
        );
    }

    private BigDecimal outstandingTotal(String applicationId) {
        return jdbcTemplate.queryForObject(
                """
                        select coalesce(sum(i.outstanding_amount), 0)
                        from loan_repayment_schedule_installment i
                        join loan_account a on a.id = i.loan_account_id
                        where a.loan_application_id = ?
                        """,
                BigDecimal.class,
                UUID.fromString(applicationId)
        );
    }

    private int paymentRowCount(String applicationId) {
        return jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from loan_payment_transaction p
                        join loan_account a on a.id = p.loan_account_id
                        where a.loan_application_id = ?
                        """,
                Integer.class,
                UUID.fromString(applicationId)
        );
    }

    private int eventCount(String applicationId, String eventType) {
        return jdbcTemplate.queryForObject(
                "select count(*) from loan_event where loan_application_id = ? and event_type = ?",
                Integer.class,
                UUID.fromString(applicationId),
                eventType
        );
    }

    private long auditEventCount(String applicationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from loan_application_audit_event where loan_application_id = ?",
                Long.class,
                UUID.fromString(applicationId)
        );
    }

    private DisbursedLoanFixture seedDisbursedLoan(String codeSuffix) throws Exception {
        String lspId = createLspViaAdmin(codeSuffix);
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String accessToken = issueApiClientToken(lspId);
        String applicationId = createApplicationViaOps(lspId, productId);
        transitionToAwaitingApproval(applicationId);
        markAllRequiredDocumentsVerified(applicationId);
        transitionToApproved(applicationId);
        disburseLoan(applicationId);

        Map<Integer, ScheduledInstallment> schedule = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                        select i.installment_number, i.id::text as installment_id, i.outstanding_amount
                        from loan_repayment_schedule_installment i
                        join loan_account a on a.id = i.loan_account_id
                        where a.loan_application_id = ?
                        order by i.installment_number
                        """,
                (RowCallbackHandler) resultSet -> schedule.put(
                        resultSet.getInt("installment_number"),
                        new ScheduledInstallment(
                                resultSet.getString("installment_id"),
                                resultSet.getBigDecimal("outstanding_amount")
                        )
                ),
                UUID.fromString(applicationId)
        );
        String loanAccountId = jdbcTemplate.queryForObject(
                "select id::text from loan_account where loan_application_id = ?",
                String.class,
                UUID.fromString(applicationId)
        );
        return new DisbursedLoanFixture(lspId, applicationId, loanAccountId, accessToken, schedule);
    }

    private String issueApiClientToken(String lspId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Settlement concurrency client " + lspId,
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode apiClient = objectMapper.readTree(created.getResponse().getContentAsString());

        MvcResult token = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                apiClient.get("clientId").asText(),
                                apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(token.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void disburseLoan(String applicationId) throws Exception {
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "Settlement Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Settlement Concurrency Borrower"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        disbursementIntentWorkflowService.executeForApplication(UUID.fromString(applicationId));
        loanDisbursementCommandService.autoResolveAfterInitiate(
                UUID.fromString(applicationId), "ops.admin", null, "settlement-concurrency-test");
    }

    private void markAllRequiredDocumentsVerified(String applicationId) {
        jdbcTemplate.update(
                """
                        update loan_application_document_checklist
                        set status = 'SUBMITTED',
                            note = 'Uploaded for settlement concurrency test',
                            updated_by_username = 'ops.user'
                        where loan_application_id = ?
                          and required = true
                        """,
                UUID.fromString(applicationId)
        );
    }

    private String createLspViaAdmin(String codeSuffix) throws Exception {
        String uniqueCode = "LSP-" + codeSuffix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", uniqueCode,
                                "name", "Settlement concurrency LSP " + codeSuffix,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createProductViaAdmin() throws Exception {
        String code = "PROD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Settlement concurrency product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private String createApplicationViaOps(String lspId, String productId) throws Exception {
        String borrowerPan = uniquePan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Settlement Concurrency Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "settle-conc+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 1));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("50000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", INSTALLMENT_COUNT);

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void transitionToAwaitingApproval(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Ready for approval"
                        ))))
                .andExpect(status().isOk());
    }

    private void transitionToApproved(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approved for settlement concurrency test"
                        ))))
                .andExpect(status().isOk());
    }

    private static String uniquePan() {
        int suffix = Math.abs(UUID.randomUUID().hashCode()) % 10_000;
        return String.format("ABCDE%04dF", suffix);
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        return "9" + String.format("%09d", hash % 1_000_000_000);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(token -> token.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private record DisbursedLoanFixture(
            String lspId,
            String applicationId,
            String loanAccountId,
            String accessToken,
            Map<Integer, ScheduledInstallment> schedule
    ) {
        String installmentId(int installmentNumber) {
            return schedule.get(installmentNumber).id();
        }

        /** Snapshot taken before any payment: the exact amount that installment will demand. */
        BigDecimal dueAmount(int installmentNumber) {
            return schedule.get(installmentNumber).dueAmount();
        }
    }

    private record ScheduledInstallment(String id, BigDecimal dueAmount) {
    }

    private record PaymentAttempt(int status, String body) {
    }
}
