package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanProductAuditEventRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.bhawana.lms.service.WebhookDeliveryClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookOutboxAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;

    @MockitoBean
    private WebhookDeliveryClient webhookDeliveryClient;

    @Autowired
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Autowired
    private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;

    @Autowired
    private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;

    @Autowired
    private LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;

    @Autowired
    private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private LoanProductAuditEventRepository loanProductAuditEventRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @BeforeEach
    void setUp() {
        webhookEventDeliveryAttemptRepository.deleteAllInBatch();
        webhookEventOutboxRepository.deleteAllInBatch();
        loanPaymentTransactionRepository.deleteAllInBatch();
        loanDisbursementRequestLogRepository.deleteAllInBatch();
        loanRepaymentScheduleInstallmentRepository.deleteAllInBatch();
        loanForeclosureQuoteRepository.deleteAllInBatch();
        loanAccountRepository.deleteAllInBatch();
        loanApplicationAuditEventRepository.deleteAllInBatch();
        loanApplicationAssignmentEventRepository.deleteAllInBatch();
        loanApplicationDocumentChecklistRepository.deleteAllInBatch();
        loanApplicationStatusTransitionRepository.deleteAllInBatch();
        loanApplicationIntakeAuditRepository.deleteAllInBatch();
        loanApplicationRepository.deleteAllInBatch();
        borrowerRepository.deleteAllInBatch();
        loanProductAuditEventRepository.deleteAllInBatch();
        loanProductLspMappingRepository.deleteAllInBatch();
        loanProductRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @Test
    void outboxCapturesSubscribedLoanLifecycleEvents() throws Exception {
        LspFixture lsp = createLsp("APEX");
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());
        updateWebhookSubscription(lsp.id(), List.of(
                "LOAN_CREATED",
                "LOAN_STATUS_CHANGED",
                "LOAN_DISBURSEMENT_UPDATED",
                "LOAN_REPAYMENT_RECORDED"
        ));

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-OUT-001");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", systemAdmin());
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);
        recordPayment(applicationId);

        mockMvc.perform(get("/api/v1/internal/admin/webhook-outbox")
                        .with(systemAdmin())
                        .queryParam("lspId", lsp.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[*].eventType", hasItems(
                        "LOAN_CREATED",
                        "LOAN_STATUS_CHANGED",
                        "LOAN_DISBURSEMENT_UPDATED",
                        "LOAN_REPAYMENT_RECORDED"
                )));
    }

    @Test
    void webhookPayloadContractsMatchLifecycleEvents() throws Exception {
        LspFixture lsp = createLsp("CONTRACT");
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());
        updateWebhookSubscription(lsp.id(), List.of(
                "LOAN_CREATED",
                "LOAN_STATUS_CHANGED",
                "LOAN_DISBURSEMENT_UPDATED",
                "LOAN_REPAYMENT_RECORDED",
                "LOAN_FORECLOSURE_COMPLETED"
        ));

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-CONTRACT-001");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", systemAdmin());
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);
        recordPayment(applicationId);
        LocalDate foreclosureDate = LocalDate.now();
        JsonNode quote = requestForeclosureQuote(applicationId, foreclosureDate);
        executeForeclosureQuote(applicationId, quote.get("id").asText(), foreclosureDate);

        List<WebhookEventOutbox> events = webhookEventOutboxRepository.findTop50ByLsp_IdOrderByCreatedAtDesc(UUID.fromString(lsp.id()));
        assertEquals(10, events.size());

        JsonNode loanCreatedEnvelope = payloadEnvelope(findFirstEvent(events, WebhookEventType.LOAN_CREATED));
        assertEquals("LOAN_CREATED", loanCreatedEnvelope.get("eventType").asText());
        assertEquals("LOAN_APPLICATION", loanCreatedEnvelope.get("aggregateType").asText());
        assertEquals(applicationId, loanCreatedEnvelope.get("aggregateId").asText());
        assertEquals(lsp.id(), loanCreatedEnvelope.get("lspId").asText());
        assertEquals(lsp.code(), loanCreatedEnvelope.get("lspCode").asText());
        JsonNode loanCreatedPayload = loanCreatedEnvelope.get("payload");
        assertEquals(applicationId, loanCreatedPayload.get("loanApplicationId").asText());
        assertEquals("EXT-CONTRACT-001", loanCreatedPayload.get("externalLoanId").asText());
        assertEquals("INITIALIZED", loanCreatedPayload.get("status").asText());
        assertFalse(loanCreatedPayload.get("borrowerId").asText().isBlank());
        assertBigDecimalEquals("45000.00", loanCreatedPayload.get("requestedAmount"));
        assertEquals(12, loanCreatedPayload.get("tenureMonths").asInt());

        List<WebhookEventOutbox> statusEvents = events.stream()
                .filter(event -> event.getEventType() == WebhookEventType.LOAN_STATUS_CHANGED)
                .toList();
        assertEquals(5, statusEvents.size());

        JsonNode reviewStatusPayload = payloadEnvelope(findStatusEvent(statusEvents, "INITIALIZED", "AWAITING_APPROVAL")).get("payload");
        assertEquals(applicationId, reviewStatusPayload.get("loanApplicationId").asText());
        assertEquals("EXT-CONTRACT-001", reviewStatusPayload.get("externalLoanId").asText());
        assertEquals("INITIALIZED", reviewStatusPayload.get("fromStatus").asText());
        assertEquals("AWAITING_APPROVAL", reviewStatusPayload.get("toStatus").asText());
        assertTrue(reviewStatusPayload.get("reasonCode").isNull());

        JsonNode approvedStatusPayload = payloadEnvelope(findStatusEvent(statusEvents, "AWAITING_APPROVAL", "APPROVED_PENDING_DISBURSAL")).get("payload");
        assertEquals("AWAITING_APPROVAL", approvedStatusPayload.get("fromStatus").asText());
        assertEquals("APPROVED_PENDING_DISBURSAL", approvedStatusPayload.get("toStatus").asText());
        assertTrue(approvedStatusPayload.get("reasonCode").isNull());

        JsonNode disbursedStatusPayload = payloadEnvelope(findStatusEvent(statusEvents, "APPROVED_PENDING_DISBURSAL", "DISBURSED")).get("payload");
        assertEquals("APPROVED_PENDING_DISBURSAL", disbursedStatusPayload.get("fromStatus").asText());
        assertEquals("DISBURSED", disbursedStatusPayload.get("toStatus").asText());
        assertTrue(disbursedStatusPayload.get("reasonCode").isNull());

        JsonNode repaymentStatusPayload = payloadEnvelope(findStatusEvent(statusEvents, "DISBURSED", "UNDER_REPAYMENT")).get("payload");
        assertEquals("DISBURSED", repaymentStatusPayload.get("fromStatus").asText());
        assertEquals("UNDER_REPAYMENT", repaymentStatusPayload.get("toStatus").asText());
        assertTrue(repaymentStatusPayload.get("reasonCode").isNull());

        JsonNode closedStatusPayload = payloadEnvelope(findStatusEvent(statusEvents, "UNDER_REPAYMENT", "CLOSED")).get("payload");
        assertEquals("UNDER_REPAYMENT", closedStatusPayload.get("fromStatus").asText());
        assertEquals("CLOSED", closedStatusPayload.get("toStatus").asText());
        assertTrue(closedStatusPayload.get("reasonCode").isNull());

        List<WebhookEventOutbox> disbursementEvents = events.stream()
                .filter(event -> event.getEventType() == WebhookEventType.LOAN_DISBURSEMENT_UPDATED)
                .toList();
        assertEquals(2, disbursementEvents.size());

        JsonNode requestedDisbursementPayload = payloadEnvelope(findDisbursementEvent(disbursementEvents, "DISBURSEMENT_REQUESTED"))
                .get("payload");
        assertEquals(applicationId, requestedDisbursementPayload.get("loanApplicationId").asText());
        assertFalse(requestedDisbursementPayload.get("loanAccountId").asText().isBlank());
        assertTrueText(requestedDisbursementPayload.get("accountNumber").asText());
        assertEquals("DISBURSEMENT_REQUESTED", requestedDisbursementPayload.get("loanAccountStatus").asText());
        assertBigDecimalEquals("45000.00", requestedDisbursementPayload.get("principalAmount"));

        JsonNode disbursedPayload = payloadEnvelope(findDisbursementEvent(disbursementEvents, "DISBURSED")).get("payload");
        assertEquals("DISBURSED", disbursedPayload.get("loanAccountStatus").asText());

        JsonNode repaymentEnvelope = payloadEnvelope(findFirstEvent(events, WebhookEventType.LOAN_REPAYMENT_RECORDED));
        assertEquals("LOAN_PAYMENT_TRANSACTION", repaymentEnvelope.get("aggregateType").asText());
        JsonNode repaymentPayload = repaymentEnvelope.get("payload");
        assertEquals(applicationId, repaymentPayload.get("loanApplicationId").asText());
        assertFalse(repaymentPayload.get("loanAccountId").asText().isBlank());
        assertFalse(repaymentPayload.get("paymentTransactionId").asText().isBlank());
        assertEquals("PAY-OUT-001", repaymentPayload.get("reference").asText());
        assertBigDecimalEquals("4136.32", repaymentPayload.get("amount"));
        assertBigDecimalEquals("4136.32", repaymentPayload.get("allocatedAmount"));
        assertBigDecimalEquals("0.00", repaymentPayload.get("unallocatedAmount"));
        assertEquals("RECEIVED", repaymentPayload.get("paymentStatus").asText());
        assertEquals(LocalDate.now().minusDays(1).toString(), repaymentPayload.get("paymentDate").asText());

        JsonNode foreclosureEnvelope = payloadEnvelope(findFirstEvent(events, WebhookEventType.LOAN_FORECLOSURE_COMPLETED));
        assertEquals("LOAN_ACCOUNT", foreclosureEnvelope.get("aggregateType").asText());
        JsonNode foreclosurePayload = foreclosureEnvelope.get("payload");
        assertEquals(applicationId, foreclosurePayload.get("loanApplicationId").asText());
        assertFalse(foreclosurePayload.get("loanAccountId").asText().isBlank());
        assertTrueText(foreclosurePayload.get("accountNumber").asText());
        assertFalse(foreclosurePayload.get("foreclosureQuoteId").asText().isBlank());
        assertEquals(1, foreclosurePayload.get("quoteVersion").asInt());
        assertEquals(foreclosureDate.toString(), foreclosurePayload.get("effectiveDate").asText());
        assertEquals("FORECLOSURE", foreclosurePayload.get("closureReason").asText());
        assertNotNull(foreclosurePayload.get("settlementAmount"));
        assertFalse(foreclosurePayload.get("closedAt").asText().isBlank());
    }

    @Test
    void outboxSkipsEventsThatAreNotSubscribed() throws Exception {
        LspFixture lsp = createLsp("NORTH");
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());
        updateWebhookSubscription(lsp.id(), List.of("LOAN_CREATED"));

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-OUT-002");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL");

        mockMvc.perform(get("/api/v1/internal/admin/webhook-outbox")
                        .with(systemAdmin())
                        .queryParam("lspId", lsp.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("LOAN_CREATED"));
    }

    @Test
    void dispatchMarksDeliveredAndStoresAttempt() throws Exception {
        LspFixture lsp = createLsp("EAST");
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());
        updateWebhookSubscription(lsp.id(), List.of("LOAN_CREATED"));
        createApplication(lsp.id(), product.id(), "EXT-OUT-003");

        given(webhookDeliveryClient.deliver(any(WebhookDeliveryClient.WebhookDeliveryRequest.class)))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(202, "accepted"));

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/dispatch")
                        .with(systemAdmin())
                        .queryParam("batchSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.delivered").value(1))
                .andExpect(jsonPath("$.retryableFailures").value(0))
                .andExpect(jsonPath("$.permanentFailures").value(0));

        mockMvc.perform(get("/api/v1/internal/admin/webhook-outbox")
                        .with(systemAdmin())
                        .queryParam("lspId", lsp.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$[0].attemptCount").value(1))
                .andExpect(jsonPath("$[0].deliveredAt").isNotEmpty());

        ArgumentCaptor<WebhookDeliveryClient.WebhookDeliveryRequest> requestCaptor =
                ArgumentCaptor.forClass(WebhookDeliveryClient.WebhookDeliveryRequest.class);
        verify(webhookDeliveryClient).deliver(requestCaptor.capture());

        WebhookDeliveryClient.WebhookDeliveryRequest request = requestCaptor.getValue();
        assertEquals("https://partner.example.com/webhooks/lms", request.endpointUrl());
        assertEquals("LOAN_CREATED", request.eventType());
        assertTrueText(request.deliveryId());
        assertTrueText(request.timestamp());
        assertEquals("v1=" + hmacSha256("whsec_outbox_test", request.timestamp() + "." + request.payloadJson()), request.signature());

        JsonNode envelope = objectMapper.readTree(request.payloadJson());
        assertEquals("LOAN_CREATED", envelope.get("eventType").asText());
        assertEquals("LOAN_APPLICATION", envelope.get("aggregateType").asText());
        assertEquals(lsp.id(), envelope.get("lspId").asText());
        assertEquals(lsp.code(), envelope.get("lspCode").asText());
        assertNotNull(envelope.get("payload"));

        List<com.bhawana.lms.domain.WebhookEventDeliveryAttempt> attempts = webhookEventDeliveryAttemptRepository.findAll();
        assertEquals(1, attempts.size());
        com.bhawana.lms.domain.WebhookEventDeliveryAttempt storedAttempt = attempts.getFirst();
        assertEquals("LOAN_CREATED", storedAttempt.getRequestEventType());
        assertEquals(request.deliveryId(), storedAttempt.getRequestDeliveryId());
        assertEquals(request.timestamp(), storedAttempt.getRequestTimestamp());
        assertEquals(request.signature(), storedAttempt.getRequestSignature());
    }

    @Test
    void dispatchMarksRetryableFailureAndStoresAttempt() throws Exception {
        LspFixture lsp = createLsp("WEST");
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());
        updateWebhookSubscription(lsp.id(), List.of("LOAN_CREATED"));
        createApplication(lsp.id(), product.id(), "EXT-OUT-004");

        given(webhookDeliveryClient.deliver(any(WebhookDeliveryClient.WebhookDeliveryRequest.class)))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(503, "partner unavailable"));

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/dispatch")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.delivered").value(0))
                .andExpect(jsonPath("$.retryableFailures").value(1))
                .andExpect(jsonPath("$.permanentFailures").value(0));

        mockMvc.perform(get("/api/v1/internal/admin/webhook-outbox")
                        .with(systemAdmin())
                        .queryParam("lspId", lsp.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RETRYABLE_FAILURE"))
                .andExpect(jsonPath("$[0].attemptCount").value(1))
                .andExpect(jsonPath("$[0].nextAttemptAt").isNotEmpty())
                .andExpect(jsonPath("$[0].lastError", containsString("503")));
    }

    @Test
    void dispatchMarksPermanentFailureForClientErrors() throws Exception {
        LspFixture lsp = createLsp("SOUTH");
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());
        updateWebhookSubscription(lsp.id(), List.of("LOAN_CREATED"));
        createApplication(lsp.id(), product.id(), "EXT-OUT-005");

        given(webhookDeliveryClient.deliver(any(WebhookDeliveryClient.WebhookDeliveryRequest.class)))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(400, "invalid payload"));

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/dispatch")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.delivered").value(0))
                .andExpect(jsonPath("$.retryableFailures").value(0))
                .andExpect(jsonPath("$.permanentFailures").value(1));

        mockMvc.perform(get("/api/v1/internal/admin/webhook-outbox")
                        .with(systemAdmin())
                        .queryParam("lspId", lsp.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PERMANENT_FAILURE"))
                .andExpect(jsonPath("$[0].attemptCount").value(1))
                .andExpect(jsonPath("$[0].lastError", containsString("400")));
    }

    private LspFixture createLsp(String codePrefix) throws Exception {
        String generatedCode = codePrefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", generatedCode,
                                "name", codePrefix + " Finance",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new LspFixture(json.get("id").asText(), json.get("code").asText());
    }

    private ProductFixture createProduct() throws Exception {
        String code = "SALARY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Salary Product " + code,
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

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new ProductFixture(json.get("id").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private void updateWebhookSubscription(String lspId, List<String> eventTypes) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://partner.example.com/webhooks/lms",
                                "signingSecret", "whsec_outbox_test",
                                "eventTypes", eventTypes
                        ))))
                .andExpect(status().isOk());
    }

    private JsonNode createApplication(String lspId, String productId, String externalLoanId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", "ABCDE1234F");
        payload.put("borrowerFullName", "Anika Sharma");
        payload.put("borrowerMobile", "9999999999");
        payload.put("borrowerEmail", "anika@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1992, 3, 10));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("78000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void transitionApplication(String applicationId, String targetStatus) throws Exception {
        transitionApplication(applicationId, targetStatus, opsUser());
    }

    private void transitionApplication(
            String applicationId,
            String targetStatus,
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor actor
    ) throws Exception {
        Map<String, Object> payload = Map.of(
                "targetStatus", targetStatus,
                "note", "Transition to " + targetStatus
        );
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private void markAllRequiredKycDocumentsVerified(String applicationId) throws Exception {
        for (String documentType : List.of(
                "PAN_CARD",
                "AADHAAR_FILE",
                "ADDRESS_PROOF",
                "INCOME_PROOF",
                "BANK_STATEMENT",
                "SELFIE_PHOTOGRAPH",
                "KFS",
                "LOAN_AGREEMENT"
        )) {
            mockMvc.perform(put("/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}",
                            applicationId,
                            documentType)
                            .with(opsUser())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "status", "VERIFIED",
                                    "note", "Verified",
                                    "reviewReason", "Checked"
                            ))))
                    .andExpect(status().isOk());
        }
    }

    private void requestDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private void resolveDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
    }

    private void recordPayment(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("4136.32"),
                                "paymentDate", LocalDate.now().minusDays(1).toString(),
                                "reference", "PAY-OUT-001",
                                "channel", "UPI",
                                "status", "RECEIVED"
                        ))))
                .andExpect(status().isOk());
    }

    private JsonNode requestForeclosureQuote(String applicationId, LocalDate effectiveDate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", effectiveDate.toString()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void executeForeclosureQuote(String applicationId, String quoteId, LocalDate settlementDate) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes/{quoteId}/execute",
                        applicationId,
                        quoteId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reference", "FC-OUT-001",
                                "note", "Contract test foreclosure",
                                "settlementDate", settlementDate.toString()
                        ))))
                .andExpect(status().isOk());
    }

    private JsonNode payloadEnvelope(WebhookEventOutbox event) throws Exception {
        return objectMapper.readTree(event.getPayloadJson());
    }

    private static WebhookEventOutbox findFirstEvent(List<WebhookEventOutbox> events, WebhookEventType eventType) {
        return events.stream()
                .filter(event -> event.getEventType() == eventType)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected webhook event type " + eventType.name()));
    }

    private WebhookEventOutbox findStatusEvent(
            List<WebhookEventOutbox> events,
            String fromStatus,
            String toStatus
    ) throws Exception {
        for (WebhookEventOutbox event : events) {
            JsonNode payload = payloadEnvelope(event).get("payload");
            if (fromStatus.equals(payload.get("fromStatus").asText()) && toStatus.equals(payload.get("toStatus").asText())) {
                return event;
            }
        }
        throw new AssertionError("Expected status webhook event " + fromStatus + " -> " + toStatus);
    }

    private WebhookEventOutbox findDisbursementEvent(List<WebhookEventOutbox> events, String loanAccountStatus) throws Exception {
        for (WebhookEventOutbox event : events) {
            JsonNode payload = payloadEnvelope(event).get("payload");
            if (loanAccountStatus.equals(payload.get("loanAccountStatus").asText())) {
                return event;
            }
        }
        throw new AssertionError("Expected disbursement webhook event for status " + loanAccountStatus);
    }

    private static String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError("Unable to compute expected webhook signature", exception);
        }
    }

    private static void assertTrueText(String value) {
        assertTrue(value != null && !value.isBlank(), "Expected non-blank text value");
    }

    private static void assertBigDecimalEquals(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
    }

    private record LspFixture(String id, String code) {
    }

    private record ProductFixture(String id) {
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
}
