package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanDocumentStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
class LoanApplicationOpsControllerDocumentDownloadAuditTest {

    private static final String CLIENT_IP = "203.0.113.50";
    private static final byte[] PAN_CONTENT = "%PDF-1.4 pan-card-pdf-content".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationLifecycleService loanApplicationLifecycleService;

    @Autowired
    private LoanDocumentStorageService loanDocumentStorageService;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void singleDocumentDownloadWritesCompleteAuditRow() throws Exception {
        String applicationId = seedApplicationWithStoredPanCard();

        MvcResult downloadResult = mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}/content",
                        applicationId,
                        "PAN_CARD")
                        .with(opsUser())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk())
                .andReturn();

        int downloadedBytes = downloadResult.getResponse().getContentAsByteArray().length;

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("SINGLE_DOCUMENT_DOWNLOADED"))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.user"))
                .andExpect(jsonPath("$[0].documentTypes.length()").value(1))
                .andExpect(jsonPath("$[0].documentTypes[0]").value("PAN_CARD"))
                .andExpect(jsonPath("$[0].actorIp").value(CLIENT_IP))
                .andExpect(jsonPath("$[0].byteCount").value(downloadedBytes))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
    }

    @Test
    void inlinePreviewServesInlineDispositionAndWritesPreviewAuditRow() throws Exception {
        String applicationId = seedApplicationWithStoredPanCard();

        MvcResult previewResult = mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}/content",
                        applicationId,
                        "PAN_CARD")
                        .param("disposition", "inline")
                        .with(opsUser())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        int previewedBytes = previewResult.getResponse().getContentAsByteArray().length;

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("SINGLE_DOCUMENT_PREVIEWED"))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.user"))
                .andExpect(jsonPath("$[0].documentTypes[0]").value("PAN_CARD"))
                .andExpect(jsonPath("$[0].actorIp").value(CLIENT_IP))
                .andExpect(jsonPath("$[0].byteCount").value(previewedBytes))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
    }

    @Test
    void defaultDispositionStillDownloadsAsAttachment() throws Exception {
        String applicationId = seedApplicationWithStoredPanCard();

        mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}/content",
                        applicationId,
                        "PAN_CARD")
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("SINGLE_DOCUMENT_DOWNLOADED"));
    }

    @Test
    void bulkZipDownloadWritesOneAuditRowCoveringAllIncludedTypes() throws Exception {
        String applicationId = seedApplicationWithStoredPanAndLoanAgreement();

        MvcResult downloadResult = mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/download-all",
                        applicationId)
                        .with(opsUser())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk())
                .andReturn();

        int zipBytes = downloadResult.getResponse().getContentAsByteArray().length;
        assertTrue(zipBytes > 0);

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("BULK_ZIP_DOWNLOADED"))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.user"))
                .andExpect(jsonPath("$[0].documentTypes.length()").value(2))
                .andExpect(jsonPath("$[0].documentTypes[?(@ == 'PAN_CARD')]").exists())
                .andExpect(jsonPath("$[0].documentTypes[?(@ == 'LOAN_AGREEMENT')]").exists())
                .andExpect(jsonPath("$[0].actorIp").value(CLIENT_IP))
                .andExpect(jsonPath("$[0].byteCount").value(zipBytes))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
    }

    @Test
    void failedSingleDocumentDownloadDoesNotWriteAuditRow() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-DOC-FAIL-001", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "No stored documents yet");

        mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}/content",
                        applicationId,
                        "PAN_CARD")
                        .with(opsUser()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void failedBulkZipDownloadDoesNotWriteAuditRow() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-ZIP-FAIL-001", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "No stored documents for ZIP");

        mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/download-all",
                        applicationId)
                        .with(opsUser()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private String seedApplicationWithStoredPanCard() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-DOC-DL-001", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        UUID applicationUuid = UUID.fromString(applicationId);

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for document download audit test");

        storeAndSubmitDocument(applicationUuid, LoanApplicationDocumentType.PAN_CARD, "pan_card.pdf", PAN_CONTENT);

        return applicationId;
    }

    private String seedApplicationWithStoredPanAndLoanAgreement() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-DOC-ZIP-001", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        UUID applicationUuid = UUID.fromString(applicationId);

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for ZIP download audit test");

        storeAndSubmitDocument(applicationUuid, LoanApplicationDocumentType.PAN_CARD, "pan_card.pdf", PAN_CONTENT);
        storeAndSubmitDocument(
                applicationUuid,
                LoanApplicationDocumentType.LOAN_AGREEMENT,
                "loan_agreement.pdf",
                "%PDF-1.4 loan-agreement-content".getBytes(StandardCharsets.UTF_8)
        );

        return applicationId;
    }

    private void storeAndSubmitDocument(
            UUID applicationUuid,
            LoanApplicationDocumentType documentType,
            String fileName,
            byte[] content
    ) {
        com.bhawana.lms.service.StoredDocument stored = loanDocumentStorageService.store(
                applicationUuid,
                documentType,
                new MockMultipartFile("file", fileName, "application/pdf", content)
        );
        loanApplicationLifecycleService.updateDocumentChecklistItem(
                applicationUuid,
                documentType,
                "ops.user",
                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                documentType.name() + " uploaded for ZIP audit test",
                stored.fileName(),
                stored.canonicalUri(),
                "seed-" + documentType.name(),
                stored.contentType(),
                stored.fileSizeBytes(),
                stored.fileChecksum(),
                stored.storageKey(),
                true
        );
    }

    private JsonNode createApplication(
            String lspId,
            String productId,
            String externalLoanId,
            String sourceChannel,
            String borrowerPan
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId,
                                productId,
                                externalLoanId,
                                sourceChannel,
                                borrowerPan,
                                "Borrower One",
                                "9000000001",
                                "borrower1@example.com",
                                LocalDate.of(1990, 1, 15),
                                "Mumbai",
                                "MH",
                                "SALARIED",
                                new BigDecimal("50000.00"),
                                new BigDecimal("100000.00"),
                                12
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void transitionApplication(String applicationId, String targetStatus, String note) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
                .andExpect(status().isOk());
    }

    private ProductFixture createProduct(String status) throws Exception {
        String code = "PRODUCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(createdJson.get("id").asText(), createdJson.get("code").asText());
    }

    private LspFixture createLsp(String status) throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lspJson = objectMapper.readTree(lspResult.getResponse().getContentAsString());
        return new LspFixture(lspJson.get("id").asText(), lspJson.get("code").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> loanApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId,
            String sourceChannel,
            String borrowerPan,
            String borrowerFullName,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            BigDecimal requestedAmount,
            int tenureMonths
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", sourceChannel);
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", borrowerFullName);
        payload.put("borrowerMobile", borrowerMobile);
        payload.put("borrowerEmail", borrowerEmail);
        payload.put("borrowerDateOfBirth", borrowerDateOfBirth);
        payload.put("borrowerCity", borrowerCity);
        payload.put("borrowerState", borrowerState);
        payload.put("borrowerEmploymentType", borrowerEmploymentType);
        payload.put("borrowerMonthlyIncome", borrowerMonthlyIncome);
        payload.put("requestedAmount", requestedAmount);
        payload.put("tenureMonths", tenureMonths);
        return payload;
    }

    private record ProductFixture(String id, String code) {
    }

    private record LspFixture(String id, String code) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
