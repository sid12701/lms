package com.bhawana.lms.web;

import com.bhawana.lms.common.api.error.DocumentStorageUnavailableException;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.service.FileSystemLoanDocumentStorageService;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class Issue92DocumentDownloadStorageFailureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationLifecycleService loanApplicationLifecycleService;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private FileSystemLoanDocumentStorageService fileSystemLoanDocumentStorageService;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        meterRegistry.clear();
    }

    @Test
    void downloadWhenStorageUnavailableReturns503WithDocumentStorageUnavailableCode() throws Exception {
        String applicationId = seedApplicationWithStoredPanCardMetadata();

        when(fileSystemLoanDocumentStorageService.openStream(anyString()))
                .thenThrow(new DocumentStorageUnavailableException(
                        "loan/test/pan.pdf",
                        "LOCAL",
                        "Disk unavailable",
                        new RuntimeException("disk gone")
                ));

        mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}/content",
                        applicationId,
                        "PAN_CARD")
                        .with(opsUser()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_STORAGE_UNAVAILABLE"));

        assertEquals(
                1.0d,
                meterRegistry.get("lms.document.storage.unavailable").tag("provider", "LOCAL").counter().count()
        );
    }

    private String seedApplicationWithStoredPanCardMetadata() throws Exception {
        return seedApplicationWithStoredPanCardMetadata(mockMvc, objectMapper, loanApplicationLifecycleService);
    }

    private static String seedApplicationWithStoredPanCardMetadata(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            LoanApplicationLifecycleService loanApplicationLifecycleService
    ) throws Exception {
        LspFixture lsp = createLsp(mockMvc, objectMapper, "ACTIVE");
        ProductFixture product = createProduct(mockMvc, objectMapper, "ACTIVE");
        mapProductToLsp(mockMvc, objectMapper, product.id(), lsp.id());

        JsonNode created = createApplication(
                mockMvc,
                objectMapper,
                lsp.id(),
                product.id(),
                "EXT-ISSUE92-503",
                "API",
                "ABCDE1234F"
        );
        String applicationId = created.get("id").asText();
        UUID applicationUuid = UUID.fromString(applicationId);

        transitionApplication(mockMvc, objectMapper, applicationId, "AWAITING_APPROVAL", "Ready for storage failure test");

        loanApplicationLifecycleService.updateDocumentChecklistItem(
                applicationUuid,
                LoanApplicationDocumentType.PAN_CARD,
                "ops.user",
                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                "PAN uploaded for storage failure test",
                "pan_card.pdf",
                "lms-doc://loan/test/pan.pdf",
                "seed-PAN_CARD",
                "application/pdf",
                18L,
                "checksum",
                "loan/test/pan.pdf",
                true
        );

        return applicationId;
    }

    private static JsonNode createApplication(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
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

    private static void transitionApplication(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            String applicationId,
            String targetStatus,
            String note
    ) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
                .andExpect(status().isOk());
    }

    private static ProductFixture createProduct(MockMvc mockMvc, ObjectMapper objectMapper, String status) throws Exception {
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

    private static LspFixture createLsp(MockMvc mockMvc, ObjectMapper objectMapper, String status) throws Exception {
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

    private static void mapProductToLsp(MockMvc mockMvc, ObjectMapper objectMapper, String productId, String lspId) throws Exception {
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
