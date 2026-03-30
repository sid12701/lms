package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
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
class LoanProductAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @BeforeEach
    void setUp() {
        loanProductLspMappingRepository.deleteAll();
        loanProductRepository.deleteAll();
    }

    @Test
    void productAdminCanCreateListAndUpdateProducts() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "SALARY-PLUS",
                                "name", "Salary Plus",
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SALARY-PLUS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String productId = createdJson.get("id").asText();

        mockMvc.perform(get("/api/v1/internal/admin/products").with(productAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("SALARY-PLUS"))
                .andExpect(jsonPath("$[0].interestRate").value(18.50));

        mockMvc.perform(put("/api/v1/internal/admin/products/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "SALARY-PLUS",
                                "name", "Salary Plus Prime",
                                "minPrincipal", new BigDecimal("10000.00"),
                                "maxPrincipal", new BigDecimal("300000.00"),
                                "interestRate", new BigDecimal("17.75"),
                                "processingFeeRate", new BigDecimal("1.95"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 30,
                                "status", "INACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Salary Plus Prime"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.maxTenureMonths").value(30));
    }

    @Test
    void invalidProductRangesAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "BROKEN",
                                "name", "Broken Product",
                                "minPrincipal", new BigDecimal("500000.00"),
                                "maxPrincipal", new BigDecimal("100000.00"),
                                "interestRate", new BigDecimal("14.00"),
                                "processingFeeRate", new BigDecimal("1.50"),
                                "minTenureMonths", 18,
                                "maxTenureMonths", 12,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void opsUserCannotAccessProductAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/products").with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void productAdminCanReplaceProductMappings() throws Exception {
        LspFixture apex = createLsp("APEX", "Apex Finance");
        LspFixture north = createLsp("NORTH", "Northbridge");
        ProductFixture product = createProduct();

        mockMvc.perform(put("/api/v1/internal/admin/products/{productId}/mappings", product.id())
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspIds", List.of(north.id(), apex.id())
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(product.id()))
                .andExpect(jsonPath("$.mappedLsps.length()").value(2))
                .andExpect(jsonPath("$.mappedLsps[0].code").value(apex.code()))
                .andExpect(jsonPath("$.mappedLsps[1].code").value(north.code()));

        mockMvc.perform(get("/api/v1/internal/admin/products/{productId}/mappings", product.id())
                        .with(productAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappedLsps.length()").value(2))
                .andExpect(jsonPath("$.mappedLsps[0].code").value(apex.code()))
                .andExpect(jsonPath("$.mappedLsps[1].code").value(north.code()));
    }

    @Test
    void invalidLspMappingsAreRejected() throws Exception {
        ProductFixture product = createProduct();

        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", product.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspIds", List.of("00000000-0000-0000-0000-000000000000")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void productAdminCanListAllProductMappings() throws Exception {
        LspFixture apex = createLsp("APEX", "Apex Finance");
        ProductFixture product = createProduct();

        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", product.id())
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspIds", List.of(apex.id())
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/product-lsp-mappings")
                        .with(productAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(product.id()))
                .andExpect(jsonPath("$[0].lspIds[0]").value(apex.id()));
    }

    private ProductFixture createProduct() throws Exception {
        String code = "SALARY-PLUS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Salary Plus " + code,
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

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(createdJson.get("id").asText(), createdJson.get("code").asText());
    }

    private LspFixture createLsp(String code, String name) throws Exception {
        String generatedCode = code + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", generatedCode,
                                "name", name + " " + code,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lspJson = objectMapper.readTree(lspResult.getResponse().getContentAsString());
        return new LspFixture(lspJson.get("id").asText(), lspJson.get("code").asText());
    }

    private record ProductFixture(String id, String code) {
    }

    private record LspFixture(String id, String code) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", java.util.List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", java.util.List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", java.util.List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
