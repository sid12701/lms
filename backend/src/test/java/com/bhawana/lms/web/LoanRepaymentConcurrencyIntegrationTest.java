package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanRepaymentScheduleInstallmentStatus;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.service.LoanEventLog;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LoanRepaymentConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @MockitoSpyBean
    private LoanEventLog loanEventLog;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        Mockito.reset(loanEventLog);
    }

    @Test
    void concurrentDifferentKeyPaymentsOnSameInstallmentAllowExactlyOneSuccess() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan();
        LocalDate postedAt = LocalDate.now().minusDays(1);
        BigDecimal emiAmount = new BigDecimal("4136.32");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<PaymentAttempt>> tasks = List.of(
                    paymentTask(fixture, emiAmount, "PAY-CONC-A", UUID.randomUUID().toString(), postedAt),
                    paymentTask(fixture, emiAmount, "PAY-CONC-B", UUID.randomUUID().toString(), postedAt)
            );

            List<Future<PaymentAttempt>> futures = executor.invokeAll(tasks);
            List<PaymentAttempt> attempts = new ArrayList<>();
            for (Future<PaymentAttempt> future : futures) {
                attempts.add(future.get());
            }

            long successCount = attempts.stream().filter(PaymentAttempt::success).count();
            long conflictCount = attempts.stream().filter(attempt -> !attempt.success()).count();
            assertEquals(1, successCount);
            assertEquals(1, conflictCount);
            assertTrue(attempts.stream().anyMatch(attempt -> "INSTALLMENT_ALREADY_PAID".equals(attempt.errorCode())));

            UUID installmentId = UUID.fromString(fixture.firstInstallmentId());
            var installment = loanRepaymentScheduleInstallmentRepository.findById(installmentId).orElseThrow();
            assertEquals(LoanRepaymentScheduleInstallmentStatus.PAID, installment.getStatus());
            assertEquals(0, emiAmount.compareTo(installment.getPaidAmount()));
            assertEquals(1L, loanPaymentTransactionRepository.count());
        }
    }

    @Test
    void allocationFailureRollsBackPaymentRow() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan();

        Mockito.doThrow(new RuntimeException("loan event append failed"))
                .when(loanEventLog)
                .append(
                        Mockito.any(),
                        Mockito.eq(LoanEventType.LOAN_REPAYMENT_RECORDED),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()
                );

        postPayment(
                fixture.applicationId(),
                fixture.firstInstallmentId(),
                new BigDecimal("4136.32"),
                "PAY-ROLLBACK-001",
                "UPI",
                UUID.randomUUID().toString(),
                LocalDate.now().minusDays(1)
        ).andExpect(status().is5xxServerError());

        assertEquals(0L, loanPaymentTransactionRepository.count());
    }

    private Callable<PaymentAttempt> paymentTask(
            DisbursedLoanFixture fixture,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            LocalDate postedAt
    ) {
        return () -> TenantScopedExecution.callAsAdmin(() -> {
            try {
                MvcResult result = postPayment(
                        fixture.applicationId(),
                        fixture.firstInstallmentId(),
                        amount,
                        reference,
                        "UPI",
                        idempotencyKey,
                        postedAt
                ).andReturn();
                int status = result.getResponse().getStatus();
                if (status == 200) {
                    return new PaymentAttempt(true, null);
                }
                String body = result.getResponse().getContentAsString();
                String errorCode = objectMapper.readTree(body).path("error").asText(null);
                return new PaymentAttempt(false, errorCode);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private DisbursedLoanFixture seedDisbursedLoan() throws Exception {
        String lspId = createLspViaAdmin("REPAY-CONC");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId);
        transitionToAwaitingApproval(applicationId);
        markAllRequiredDocumentsVerified(applicationId);
        transitionToApproved(applicationId);
        disburseLoan(applicationId);
        String firstInstallmentId = installmentIdAt(applicationId, 1);
        return new DisbursedLoanFixture(applicationId, firstInstallmentId);
    }

    private ResultActions postPayment(
            String applicationId,
            String installmentId,
            BigDecimal amount,
            String reference,
            String channel,
            String idempotencyKey,
            LocalDate postedAt
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetInstallmentId", installmentId);
        body.put("amount", amount);
        body.put("postedAt", postedAt.toString());
        body.put("channel", channel);
        body.put("reference", reference);

        return mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                .with(systemAdmin())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String installmentIdAt(String applicationId, int installmentNumber) {
        return jdbcTemplate.queryForObject(
                """
                        select i.id::text
                        from loan_repayment_schedule_installment i
                        join loan_account a on a.id = i.loan_account_id
                        where a.loan_application_id = ?
                          and i.installment_number = ?
                        """,
                String.class,
                UUID.fromString(applicationId),
                installmentNumber
        );
    }

    private void disburseLoan(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
    }

    private void markAllRequiredDocumentsVerified(String applicationId) {
        jdbcTemplate.update(
                """
                        update loan_application_document_checklist
                        set status = 'SUBMITTED',
                            note = 'Uploaded for repayment concurrency test',
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
                                "name", "Repayment concurrency LSP " + codeSuffix,
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
                                "name", "Repayment concurrency product " + code,
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
        payload.put("borrowerFullName", "Repayment Concurrency Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "repay-conc+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 1));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("50000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);

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
                                "note", "Approved for repayment concurrency test"
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

    private record DisbursedLoanFixture(String applicationId, String firstInstallmentId) {
    }

    private record PaymentAttempt(boolean success, String errorCode) {
    }
}
