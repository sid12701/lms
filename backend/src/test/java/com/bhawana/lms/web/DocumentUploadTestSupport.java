package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

final class DocumentUploadTestSupport {

    private DocumentUploadTestSupport() {
    }

    static Seed seedLspApplication(MockMvc mockMvc, ObjectMapper objectMapper) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult lspResult = mockMvc.perform(
                        post("/api/v1/internal/admin/lsps")
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
                        post("/api/v1/internal/admin/products")
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
                        post("/api/v1/internal/admin/api-clients")
                                .with(opsJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"lspId":"%s","name":"doc-upload-client"}
                                        """.formatted(lspId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode client = objectMapper.readTree(clientResult.getResponse().getContentAsString());

        mockMvc.perform(
                        post("/api/v1/auth/token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"clientId":"%s","clientSecret":"%s"}
                                        """.formatted(client.get("clientId").asText(), client.get("clientSecret").asText())))
                .andExpect(status().isOk());

        MvcResult appResult = mockMvc.perform(
                        post("/api/v1/lsp/loan-applications")
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

    static org.springframework.test.web.servlet.request.RequestPostProcessor opsJwt() {
        return jwt().jwt(builder -> builder
                        .subject("ops.admin")
                        .claim("roles", List.of("SYSTEM_ADMIN", "PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN", () -> "ROLE_PRODUCT_ADMIN");
    }

    static org.springframework.test.web.servlet.request.RequestPostProcessor lspApiClient(String clientId, UUID lspId) {
        return jwt().jwt(builder -> builder
                        .subject(clientId)
                        .claim("roles", List.of("LSP_API_CLIENT"))
                        .claim("lspId", lspId.toString()))
                .authorities(() -> "ROLE_LSP_API_CLIENT");
    }

    record Seed(UUID lspId, String clientId, UUID applicationId) {
    }
}
