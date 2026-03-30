package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductAuditEventRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanApplicationOpsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductAuditEventRepository loanProductAuditEventRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private LspRepository lspRepository;

    @BeforeEach
    void setUp() {
        loanApplicationRepository.deleteAllInBatch();
        borrowerRepository.deleteAllInBatch();
        apiClientRepository.deleteAllInBatch();
        appUserRepository.deleteAllInBatch();
        loanProductAuditEventRepository.deleteAllInBatch();
        loanProductLspMappingRepository.deleteAllInBatch();
        loanProductRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @Test
    void opsUserCanCreateAndListLoanApplicationsWhileReusingBorrowersByPan() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        String firstBorrowerId = createApplication(lsp.id(), product.id(), "EXT-001", "API", "ABCDE1234F")
                .get("borrowerId").asText();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "externalLoanId", "EXT-002",
                                "sourceChannel", "PARTNER_PORTAL",
                                "borrowerPan", "ABCDE1234F",
                                "borrowerFullName", "Anika Sharma",
                                "borrowerMobile", "9999999999",
                                "borrowerEmail", "anika.updated@example.com",
                                "requestedAmount", new BigDecimal("50000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.borrowerId").value(firstBorrowerId))
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-002"))
                .andExpect(jsonPath("$[0].borrowerId").value(firstBorrowerId))
                .andExpect(jsonPath("$[1].externalLoanId").value("EXT-001"));
    }

    @Test
    void invalidLoanAmountIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "externalLoanId", "EXT-003",
                                "sourceChannel", "API",
                                "borrowerPan", "PQRSX4321Z",
                                "borrowerFullName", "Riya Kapoor",
                                "borrowerMobile", "9898989898",
                                "borrowerEmail", "riya@example.com",
                                "requestedAmount", new BigDecimal("999999.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void duplicateExternalLoanIdForSameLspIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        createApplication(lsp.id(), product.id(), "EXT-010", "API", "LMNOP4321Q");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "externalLoanId", "EXT-010",
                                "sourceChannel", "API",
                                "borrowerPan", "ZXCVB1234N",
                                "borrowerFullName", "Rahul Shah",
                                "borrowerMobile", "9876543210",
                                "borrowerEmail", "rahul@example.com",
                                "requestedAmount", new BigDecimal("45000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void unmappedProductIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "externalLoanId", "EXT-020",
                                "sourceChannel", "API",
                                "borrowerPan", "ABCDE1234F",
                                "borrowerFullName", "Anika Sharma",
                                "borrowerMobile", "9999999999",
                                "borrowerEmail", "anika@example.com",
                                "requestedAmount", new BigDecimal("45000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
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
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lspId,
                                "productId", productId,
                                "externalLoanId", externalLoanId,
                                "sourceChannel", sourceChannel,
                                "borrowerPan", borrowerPan,
                                "borrowerFullName", "Anika Sharma",
                                "borrowerMobile", "9999999999",
                                "borrowerEmail", "anika@example.com",
                                "requestedAmount", new BigDecimal("45000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
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
