package com.bhawana.lms.web;

import com.bhawana.lms.config.TimeConfig;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class Issue86RepaymentIdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private DisbursementIntentWorkflowService disbursementIntentWorkflowService;

    @Autowired
    private LoanDisbursementCommandService loanDisbursementCommandService;

    @Test
    void duplicatePaymentWithSameKeyAndBodyReturnsOriginalPayment() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan();
        String idempotencyKey = UUID.randomUUID().toString();
        LocalDate postedAt = LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(1);

        MvcResult first = postPayment(
                fixture.applicationId(),
                fixture.firstInstallmentId(),
                new BigDecimal("4136.32"),
                "PAY-RETRY-001",
                "UPI",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk()).andReturn();

        String firstPaymentId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        MvcResult second = postPayment(
                fixture.applicationId(),
                fixture.firstInstallmentId(),
                new BigDecimal("4136.32"),
                "PAY-RETRY-001",
                "UPI",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk()).andReturn();

        String secondPaymentId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertEquals(firstPaymentId, secondPaymentId);

        Long paymentCount = jdbcTemplate.queryForObject(
                "select count(*) from loan_payment_transaction where idempotency_key = ?",
                Long.class,
                idempotencyKey
        );
        assertEquals(1L, paymentCount);
    }

    @Test
    void duplicatePaymentWithSameKeyButDifferentAmountReturnsConflict() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan();
        String idempotencyKey = UUID.randomUUID().toString();
        LocalDate postedAt = LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(1);

        postPayment(
                fixture.applicationId(),
                fixture.firstInstallmentId(),
                new BigDecimal("4136.32"),
                "PAY-MISMATCH-001",
                "UPI",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk());

        postPayment(
                fixture.applicationId(),
                fixture.firstInstallmentId(),
                new BigDecimal("5000.00"),
                "PAY-MISMATCH-001",
                "UPI",
                idempotencyKey,
                postedAt
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "Idempotency-Key has already been used for a different request."
                ));
    }

    @Test
    void concurrentPaymentsWithSameKeyProduceSingleRowAndMatchingResponses() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan();
        String idempotencyKey = UUID.randomUUID().toString();
        LocalDate postedAt = LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                tasks.add(() -> TenantScopedExecution.callAsAdmin(() -> {
                    try {
                        return postPayment(
                                fixture.applicationId(),
                                fixture.firstInstallmentId(),
                                new BigDecimal("4136.32"),
                                "PAY-RACE-001",
                                "UPI",
                                idempotencyKey,
                                postedAt
                        ).andReturn();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }

            List<Future<MvcResult>> futures = executor.invokeAll(tasks);
            List<String> responseBodies = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                int status = result.getResponse().getStatus();
                if (status != 200) {
                    // A duplicate that arrives while the winner is still in flight is answered
                    // with a bounded conflict, not the receipt. Retrying the identical request
                    // once the winner has committed must replay its stored response.
                    assertEquals(
                            409,
                            status,
                            "a concurrent duplicate may only be refused with a bounded conflict: "
                                    + result.getResponse().getContentAsString()
                    );
                    String error = objectMapper.readTree(result.getResponse().getContentAsString())
                            .get("error").asText();
                    assertTrue(
                            error.equals("IDEMPOTENCY_IN_PROGRESS") || error.equals("CONCURRENT_MODIFICATION"),
                            "unexpected conflict code for a same-key duplicate: " + error
                    );
                    result = postPayment(
                            fixture.applicationId(),
                            fixture.firstInstallmentId(),
                            new BigDecimal("4136.32"),
                            "PAY-RACE-001",
                            "UPI",
                            idempotencyKey,
                            postedAt
                    ).andReturn();
                    assertEquals(
                            200,
                            result.getResponse().getStatus(),
                            "retry after the bounded conflict must replay the winner's response: "
                                    + result.getResponse().getContentAsString()
                    );
                }
                responseBodies.add(result.getResponse().getContentAsString());
            }

            assertEquals(5, responseBodies.size());
            // Every resolved response — winner and replays alike — is the same receipt.
            assertEquals(
                    1,
                    responseBodies.stream().distinct().count(),
                    "resolved bodies diverged: " + responseBodies.stream().distinct().toList()
            );
            assertEquals(
                    1,
                    responseBodies.stream()
                            .map(body -> readPaymentId(body))
                            .distinct()
                            .count()
            );
            assertEquals(
                    1L,
                    jdbcTemplate.queryForObject(
                            "select count(*) from loan_payment_transaction where idempotency_key = ?",
                            Long.class,
                            idempotencyKey
                    )
            );
        }
    }

    @Test
    void legacyPaymentWithNullFingerprintAcceptsRetryWithSameKey() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan();
        String idempotencyKey = UUID.randomUUID().toString();
        LocalDate postedAt = LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(1);

        MvcResult first = postPayment(
                fixture.applicationId(),
                fixture.firstInstallmentId(),
                new BigDecimal("4136.32"),
                "PAY-LEGACY-001",
                "UPI",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk()).andReturn();

        jdbcTemplate.update(
                "update loan_payment_transaction set request_fingerprint = null where idempotency_key = ?",
                idempotencyKey
        );

        String firstPaymentId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        MvcResult retry = postPayment(
                fixture.applicationId(),
                fixture.firstInstallmentId(),
                new BigDecimal("4136.32"),
                "PAY-LEGACY-001",
                "UPI",
                idempotencyKey,
                postedAt
        ).andExpect(status().isOk()).andReturn();

        assertEquals(
                firstPaymentId,
                objectMapper.readTree(retry.getResponse().getContentAsString()).get("id").asText()
        );
    }

    private DisbursedLoanFixture seedDisbursedLoan() throws Exception {
        String lspId = createLspViaAdmin("ISSUE86-LSP");
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

    private org.springframework.test.web.servlet.ResultActions postPayment(
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
        // Durable intent is the only initiation path — seed the frozen beneficiary
        // instruction, raise the intent, then execute it (IMPS success disburses atomically).
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "Issue86 Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Issue 86 Borrower"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        disbursementIntentWorkflowService.executeForApplication(UUID.fromString(applicationId));
        loanDisbursementCommandService.autoResolveAfterInitiate(
                UUID.fromString(applicationId), "ops.admin", null, "issue86-test");
    }

    private void markAllRequiredDocumentsVerified(String applicationId) {
        jdbcTemplate.update(
                """
                        update loan_application_document_checklist
                        set status = 'SUBMITTED',
                            note = 'Uploaded for issue 86 test',
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
                                "name", "Issue 86 LSP " + codeSuffix,
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
                                "name", "Issue 86 product " + code,
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
        payload.put("borrowerFullName", "Issue 86 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "issue86+" + borrowerPan.toLowerCase() + "@example.com");
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
                                "note", "Approved for issue 86 test"
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

    private String readPaymentId(String responseBody) {
        try {
            return objectMapper.readTree(responseBody).get("id").asText();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("payment response is not readable JSON: " + responseBody, exception);
        }
    }

    private record DisbursedLoanFixture(String applicationId, String firstInstallmentId) {
    }

    /**
     * The five-way payment burst pins one pool connection per racer for the whole
     * payment transaction, including the loan row-lock wait, while the shared test
     * pool lazily creates connections (minimum-idle=0) and retires them at
     * max-lifetime. Size 5 leaves no headroom, so on a loaded CI runner a racer's
     * borrow can outlast connection-timeout and fail the request with a 500.
     * {@code spring.datasource.hikari.*} cannot be overridden per test class —
     * {@code PostgresTestEnvironmentPostProcessor} binds it ahead of every
     * test-level property source — so this configuration widens the two Hikari
     * pools directly, for this class's context only.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyPoolSizingConfiguration {

        @Bean
        static BeanPostProcessor widenHikariPoolsForConcurrencyBurst() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof HikariDataSource hikariDataSource
                            && ("adminDataSource".equals(beanName)
                                    || "tenantPhysicalDataSource".equals(beanName))) {
                        hikariDataSource.setMaximumPoolSize(10);
                    }
                    return bean;
                }
            };
        }
    }
}
