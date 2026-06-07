package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.LoanProductAuditEventRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
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
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ProductLspMappingAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private LoanProductAuditEventRepository loanProductAuditEventRepository;

    @BeforeEach
    void setUp() {
        loanProductAuditEventRepository.deleteAll();
        loanProductLspMappingRepository.deleteAll();
    }

    @Test
    void productAdminCanUpsertAndListProductLspMappings() throws Exception {
        LspFixture lsp = createLsp();
        ProductFixture product = createProduct();

        mockMvc.perform(post("/api/v1/internal/admin/product-lsp-mappings/entries")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lspId").value(lsp.id()))
                .andExpect(jsonPath("$.productId").value(product.id()))
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/v1/internal/admin/product-lsp-mappings/entries")
                        .with(productAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lspId").value(lsp.id()))
                .andExpect(jsonPath("$[0].productId").value(product.id()))
                .andExpect(jsonPath("$[0].enabled").value(true));

        mockMvc.perform(get("/api/v1/internal/admin/products/{productId}/audit-events", product.id())
                        .with(productAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("PRODUCT_MAPPING_ENTRY_UPDATED"))
                .andExpect(jsonPath("$[0].actorUsername").value("product.admin"))
                .andExpect(jsonPath("$[0].summary").value(org.hamcrest.Matchers.containsString(lsp.code())));
    }

    @Test
    void upsertUpdatesExistingMappingInsteadOfDuplicating() throws Exception {
        LspFixture lsp = createLsp();
        ProductFixture product = createProduct();

        MvcResult firstResult = mockMvc.perform(post("/api/v1/internal/admin/product-lsp-mappings/entries")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        String mappingId = firstJson.get("id").asText();

        mockMvc.perform(post("/api/v1/internal/admin/product-lsp-mappings/entries")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "enabled", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mappingId))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/v1/internal/admin/product-lsp-mappings/entries")
                        .with(productAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(mappingId))
                .andExpect(jsonPath("$[0].enabled").value(false));

        org.assertj.core.api.Assertions.assertThat(loanProductLspMappingRepository.count()).isEqualTo(1);
    }

    @Test
    void opsUserCannotManageProductLspMappings() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/product-lsp-mappings/entries")
                        .with(opsUser()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/internal/admin/product-lsp-mappings/entries")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", UUID.randomUUID(),
                                "productId", UUID.randomUUID(),
                                "enabled", true
                        ))))
                .andExpect(status().isForbidden());
    }

    private ProductFixture createProduct() throws Exception {
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
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(createdJson.get("id").asText(), createdJson.get("code").asText());
    }

    private LspFixture createLsp() throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
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
