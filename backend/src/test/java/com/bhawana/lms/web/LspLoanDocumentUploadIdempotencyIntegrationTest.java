package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.service.FileSystemLoanDocumentStorageService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LspLoanDocumentUploadIdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanApplicationDocumentChecklistRepository checklistRepository;

    @Autowired
    private FileSystemLoanDocumentStorageService fileSystemLoanDocumentStorageService;

    @Autowired
    private LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        lspApiIdempotencyRecordRepository.deleteAll();
    }

    @Test
    void singleUploadWithSameKeyReplaysWithoutDuplicateChecklistOrStorageObjects() throws Exception {
        DocumentFixture fixture = createUploadFixture();
        String key = UUID.randomUUID().toString();
        byte[] fileBytes = "%PDF-1.4 idempotent upload".getBytes(StandardCharsets.UTF_8);

        MvcResult first = mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents",
                        fixture.applicationId())
                        .file(new MockMultipartFile(
                                "file",
                                "pan-idempotent.pdf",
                                "application/pdf",
                                fileBytes
                        ))
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", key)
                        .param("documentType", "PAN_CARD")
                        .param("note", "first upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("PAN_CARD"))
                .andReturn();

        int storedFilesAfterFirst = fileSystemLoanDocumentStorageService.listAll("").size();

        mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents",
                        fixture.applicationId())
                        .file(new MockMultipartFile(
                                "file",
                                "pan-idempotent.pdf",
                                "application/pdf",
                                fileBytes
                        ))
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", key)
                        .param("documentType", "PAN_CARD")
                        .param("note", "first upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(
                        objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText()));

        assertEquals(1, checklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(
                UUID.fromString(fixture.applicationId())).stream()
                .filter(item -> item.getDocumentType().name().equals("PAN_CARD"))
                .count());
        assertEquals(storedFilesAfterFirst, fileSystemLoanDocumentStorageService.listAll("").size());
    }

    @Test
    void singleUploadWithSameKeyButDifferentBytesConflicts() throws Exception {
        DocumentFixture fixture = createUploadFixture();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents",
                        fixture.applicationId())
                        .file(new MockMultipartFile(
                                "file",
                                "pan-a.pdf",
                                "application/pdf",
                                "%PDF content-a".getBytes(StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", key)
                        .param("documentType", "PAN_CARD"))
                .andExpect(status().isOk());

        mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents",
                        fixture.applicationId())
                        .file(new MockMultipartFile(
                                "file",
                                "pan-b.pdf",
                                "application/pdf",
                                "%PDF content-b".getBytes(StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", key)
                        .param("documentType", "PAN_CARD"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void batchUploadWithSameKeyReplaysWithoutDuplicateRows() throws Exception {
        DocumentFixture fixture = createUploadFixture();
        String key = UUID.randomUUID().toString();
        byte[] panBytes = "%PDF pan".getBytes(StandardCharsets.UTF_8);
        byte[] aadhaarBytes = "%PDF aadhaar".getBytes(StandardCharsets.UTF_8);

        String documentsJson = objectMapper.writeValueAsString(List.of(
                Map.of("documentType", "PAN_CARD", "note", "batch pan"),
                Map.of("documentType", "AADHAAR_FILE", "note", "batch aadhaar")
        ));

        MvcResult first = mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents/batch",
                        fixture.applicationId())
                        .file(new MockMultipartFile(
                                "documents",
                                "documents.json",
                                MediaType.APPLICATION_JSON_VALUE,
                                documentsJson.getBytes(StandardCharsets.UTF_8)
                        ))
                        .file(new MockMultipartFile(
                                "files",
                                "pan.pdf",
                                "application/pdf",
                                panBytes
                        ))
                        .file(new MockMultipartFile(
                                "files",
                                "aadhaar.pdf",
                                "application/pdf",
                                aadhaarBytes
                        ))
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents/batch",
                        fixture.applicationId())
                        .file(new MockMultipartFile(
                                "documents",
                                "documents.json",
                                MediaType.APPLICATION_JSON_VALUE,
                                documentsJson.getBytes(StandardCharsets.UTF_8)
                        ))
                        .file(new MockMultipartFile(
                                "files",
                                "pan.pdf",
                                "application/pdf",
                                panBytes
                        ))
                        .file(new MockMultipartFile(
                                "files",
                                "aadhaar.pdf",
                                "application/pdf",
                                aadhaarBytes
                        ))
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(
                        objectMapper.readTree(first.getResponse().getContentAsString()).get(0).get("id").asText()));

        assertEquals(2, checklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(
                UUID.fromString(fixture.applicationId())).stream()
                .filter(item -> item.getStatus().name().equals("SUBMITTED"))
                .count());
    }

    private DocumentFixture createUploadFixture() throws Exception {
        LspFixture lsp = createLsp();
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());
        JsonNode apiClient = createApiClient(lsp.id());
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );
        JsonNode application = createExternalApplication(accessToken, lsp.id(), product.id(), "DOC-IDMP-" + UUID.randomUUID());
        return new DocumentFixture(application.get("id").asText(), accessToken);
    }

    private JsonNode createExternalApplication(
            String accessToken,
            String lspId,
            String productId,
            String lspLoanId
    ) throws Exception {
        LinkedHashMap<String, Object> payload = defaultExternalApplicationPayload(lspId, productId, lspLoanId);
        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private LinkedHashMap<String, Object> defaultExternalApplicationPayload(
            String lspId,
            String productId,
            String lspLoanId
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("lspLoanId", lspLoanId);
        payload.put("fullName", "Document Idempotency Borrower");
        payload.put("emailAddress", lspLoanId.toLowerCase() + "@example.com");
        payload.put("mobileNumber", "9" + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000)));
        payload.put("dob", "1992-03-10");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Test Father");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", "ABCDE1234F");
        payload.put("loanAmount", new BigDecimal("45000.00"));
        payload.put("interestRate", new BigDecimal("18.50"));
        payload.put("loanTenure", 12);
        payload.put("addressLine1", "Test Street");
        payload.put("addressCity", "Mumbai");
        payload.put("addressState", "Maharashtra");
        payload.put("addressZipcode", "400001");
        payload.put("employmentStatus", "SALARIED");
        payload.put("organizationName", "Test Corp");
        payload.put("empId", "EMP-001");
        payload.put("employmentCity", "Mumbai");
        payload.put("employmentState", "Maharashtra");
        payload.put("employmentZip", "400001");
        payload.put("monthlyIncome", new BigDecimal("78000.00"));
        payload.put("annualIncome", new BigDecimal("936000.00"));
        payload.put("bankAccountNumber", "123456789012");
        payload.put("bankName", "Demo Bank");
        payload.put("ifscCode", "HDFC0001234");
        payload.put("accountHolderName", "Document Idempotency Borrower");
        payload.put("referencePersonName", "Ref Person");
        payload.put("referencePersonNumber", "9876543210");
        return payload;
    }

    private LspFixture createLsp() throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new LspFixture(json.get("id").asText(), json.get("code").asText());
    }

    private ProductFixture createProduct() throws Exception {
        String code = "PRD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
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
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new ProductFixture(json.get("id").asText(), json.get("code").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private JsonNode createApiClient(String lspId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Doc Idempotency Client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String issueClientCredentialsToken(String clientId, String clientSecret) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                clientId,
                                clientSecret
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private record DocumentFixture(String applicationId, String accessToken) {
    }

    private record LspFixture(String id, String code) {
    }

    private record ProductFixture(String id, String code) {
    }
}
