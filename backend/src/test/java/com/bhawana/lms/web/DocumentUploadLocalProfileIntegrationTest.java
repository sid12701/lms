package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Rate limiting is forced off: the repo-root .env (imported by the local profile) may enable it,
// and RateLimitConfig connects to Redis eagerly at startup — this test only needs DB + storage.
@SpringBootTest(properties = "app.rate-limit.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class DocumentUploadLocalProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    @EnabledIf("supabaseEnvPresent")
    void lspMultipartDocumentUploadDoesNotReturn500() throws Exception {
        Seed seed = seedLspApplication();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan.pdf",
                "application/pdf",
                Files.readAllBytes(Path.of("../postman/assets/sample-pan.pdf"))
        );

        assertThatCode(() -> mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents", seed.applicationId())
                        .file(file)
                        .param("documentType", LoanApplicationDocumentType.PAN_CARD.name())
                        .with(lspApiClient(seed.clientId(), seed.lspId()))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andDo(print())
                .andExpect(status().is2xxSuccessful()))
                .doesNotThrowAnyException();
    }

    static boolean supabaseEnvPresent() {
        try {
            Map<String, String> env = loadRepoRootEnv();
            return env.getOrDefault("LMS_DB_URL", "").contains("supabase");
        } catch (Exception exception) {
            return false;
        }
    }

    private Seed seedLspApplication() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult lspResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/internal/admin/lsps")
                                .with(opsJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"code":"DOC-UP-%s","name":"Doc Upload Test","status":"ACTIVE"}
                                        """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lsp = objectMapper.readTree(lspResult.getResponse().getContentAsString());
        UUID lspId = UUID.fromString(lsp.get("id").asText());

        MvcResult productResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/internal/admin/products")
                                .with(opsJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"code":"DOC-P-%s","name":"Doc Product","minPrincipal":10000,"maxPrincipal":500000,
                                        "interestRate":14.5,"processingFeeRate":1.5,"minTenureMonths":6,"maxTenureMonths":36,"status":"ACTIVE"}
                                        """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode product = objectMapper.readTree(productResult.getResponse().getContentAsString());

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/v1/internal/admin/products/{productId}/mappings", product.get("id").asText())
                                .with(opsJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"lspIds":["%s"]}
                                        """.formatted(lspId)))
                .andExpect(status().isOk());

        MvcResult clientResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/internal/admin/api-clients")
                                .with(opsJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"lspId":"%s","name":"doc-upload-client"}
                                        """.formatted(lspId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode client = objectMapper.readTree(clientResult.getResponse().getContentAsString());

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"clientId":"%s","clientSecret":"%s"}
                                        """.formatted(client.get("clientId").asText(), client.get("clientSecret").asText())))
                .andExpect(status().isOk());

        MvcResult appResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/lsp/loan-applications")
                                .with(lspApiClient(client.get("clientId").asText(), lspId))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(minimalLoanBody(lspId, UUID.fromString(product.get("id").asText()), suffix)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode app = objectMapper.readTree(appResult.getResponse().getContentAsString());

        return new Seed(
                lspId,
                client.get("clientId").asText(),
                UUID.fromString(app.get("id").asText())
        );
    }

    private static String minimalLoanBody(UUID lspId, UUID productId, String suffix) {
        return """
                {"lspId":"%s","productId":"%s","lspLoanId":"EXT-%s","fullName":"Doc Borrower",
                "emailAddress":"doc%s@example.com","mobileNumber":"98765%05d","dob":"1990-05-15",
                "gender":"MALE","maritalStatus":"SINGLE","fatherName":"Parent","aadharNumber":"%012d",
                "panNumber":"ABCDE%04dF","loanAmount":150000,"interestRate":14.5,"loanTenure":12,
                "addressLine1":"42 Demo Street","addressCity":"Mumbai","addressState":"MH","addressZipcode":"400001",
                "employmentStatus":"SALARIED","organizationName":"Demo Corp","monthlyIncome":60000,"annualIncome":720000,
                "bankAccountNumber":"1234567890","bankName":"HDFC Bank","ifscCode":"HDFC0001234",
                "accountHolderName":"Doc Borrower","referencePersonName":"Ref","referencePersonNumber":"9123456780"}
                """.formatted(
                lspId,
                productId,
                suffix,
                suffix,
                Integer.parseInt(suffix.replaceAll("\\D", "1")) % 100000,
                Math.abs(suffix.hashCode()) % 1_000_000_000_000L,
                Math.abs(suffix.hashCode()) % 10000
        );
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor opsJwt() {
        return jwt().jwt(builder -> builder
                        .subject("ops.admin")
                        .claim("roles", List.of("SYSTEM_ADMIN", "PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN", () -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor lspApiClient(String clientId, UUID lspId) {
        return jwt().jwt(builder -> builder
                        .subject(clientId)
                        .claim("roles", List.of("LSP_API_CLIENT"))
                        .claim("lspId", lspId.toString()))
                .authorities(() -> "ROLE_LSP_API_CLIENT");
    }

    private static Map<String, String> loadRepoRootEnv() throws Exception {
        Path envFile = Path.of("..", ".env").normalize();
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(envFile)) {
            if (line.isBlank() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int idx = line.indexOf('=');
            env.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return env;
    }

    private record Seed(UUID lspId, String clientId, UUID applicationId) {
    }
}
