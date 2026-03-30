package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanProductAuditEventRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.hamcrest.Matchers.containsString;

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
    private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;

    @Autowired
    private LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository;

    @Autowired
    private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;

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
    private AppRoleRepository appRoleRepository;

    @Autowired
    private LspRepository lspRepository;

    @BeforeEach
    void setUp() {
        loanApplicationAssignmentEventRepository.deleteAllInBatch();
        loanApplicationStatusTransitionRepository.deleteAllInBatch();
        loanApplicationIntakeAuditRepository.deleteAllInBatch();
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
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-002",
                                "PARTNER_PORTAL",
                                "ABCDE1234F",
                                "Anika Sharma",
                                "9999999999",
                                "anika.updated@example.com",
                                LocalDate.of(1994, 2, 14),
                                "Bengaluru",
                                "Karnataka",
                                "SALARIED",
                                new BigDecimal("85000.00"),
                                new BigDecimal("50000.00"),
                                12
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.borrowerId").value(firstBorrowerId))
                .andExpect(jsonPath("$.borrowerCity").value("Bengaluru"))
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
    void opsUserCanFilterLoanApplicationsByLspProductAndSearchQuery() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture salary = createProduct("ACTIVE");
        ProductFixture merchant = createProduct("ACTIVE");
        mapProductToLsp(salary.id(), apex.id());
        mapProductToLsp(merchant.id(), north.id());

        createApplication(apex.id(), salary.id(), "EXT-100", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                north.id(),
                                merchant.id(),
                                "NORTH-200",
                                "PARTNER_PORTAL",
                                "ZXCVB1234N",
                                "Rahul Shah",
                                "9876543210",
                                "rahul@example.com",
                                LocalDate.of(1991, 9, 12),
                                "Delhi",
                                "Delhi",
                                "SELF_EMPLOYED",
                                new BigDecimal("92000.00"),
                                new BigDecimal("45000.00"),
                                12
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("lspId", north.id())
                        .queryParam("productId", merchant.id())
                        .queryParam("q", "rahul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("NORTH-200"))
                .andExpect(jsonPath("$[0].borrowerFullName").value("Rahul Shah"));
    }

    @Test
    void opsUserCanFilterLoanApplicationsByStatusAndSourceChannel() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        createApplication(lsp.id(), product.id(), "EXT-301", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "externalLoanId", "EXT-302",
                                "sourceChannel", "PARTNER_PORTAL",
                                "borrowerPan", "ZXCVB1234N",
                                "borrowerFullName", "Rahul Shah",
                                "borrowerMobile", "9876543210",
                                "borrowerEmail", "rahul@example.com",
                                "requestedAmount", new BigDecimal("45000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("status", "RECEIVED")
                        .queryParam("sourceChannel", "PARTNER_PORTAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-302"))
                .andExpect(jsonPath("$[0].sourceChannel").value("PARTNER_PORTAL"));
    }

    @Test
    void opsUserCanInspectLoanApplicationIntakeAudit() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-901", "API", "ABCDE1234F");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/intake-audits", created.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].loanApplicationId").value(created.get("id").asText()))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.user"))
                .andExpect(jsonPath("$[0].payloadJson", containsString("\"externalLoanId\":\"EXT-901\"")))
                .andExpect(jsonPath("$[0].payloadJson", containsString("\"sourceChannel\":\"API\"")));
    }

    @Test
    void opsUserCanInspectLoanApplicationDetailAndStatusHistory() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-951", "API", "ABCDE1234F");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", created.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.get("id").asText()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.borrowerDateOfBirth").value("1992-03-10"))
                .andExpect(jsonPath("$.borrowerCity").value("Mumbai"))
                .andExpect(jsonPath("$.borrowerState").value("Maharashtra"))
                .andExpect(jsonPath("$.borrowerEmploymentType").value("SALARIED"))
                .andExpect(jsonPath("$.borrowerMonthlyIncome").value(78000.00))
                .andExpect(jsonPath("$.updatedAt").exists());

        transitionApplication(created.get("id").asText(), "UNDER_REVIEW", "Assigned for analyst review");
        transitionApplication(created.get("id").asText(), "APPROVED", "Approved after validation");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", created.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].note").value("Approved after validation"))
                .andExpect(jsonPath("$[1].fromStatus").value("RECEIVED"))
                .andExpect(jsonPath("$[1].toStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[1].note").value("Assigned for analyst review"));
    }

    @Test
    void invalidLoanApplicationStatusTransitionsAreRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-961", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", created.get("id").asText())
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED",
                                "note", "Skipping review"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        transitionApplication(created.get("id").asText(), "UNDER_REVIEW", "Started review");
        transitionApplication(created.get("id").asText(), "APPROVED", "Approved after checks");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", created.get("id").asText())
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "REJECTED",
                                "note", "Cannot revert after approval"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void invalidLoanAmountIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-003",
                                "API",
                                "PQRSX4321Z",
                                "Riya Kapoor",
                                "9898989898",
                                "riya@example.com",
                                LocalDate.of(1995, 1, 11),
                                "Jaipur",
                                "Rajasthan",
                                "SALARIED",
                                new BigDecimal("64000.00"),
                                new BigDecimal("999999.00"),
                                12
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
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-010",
                                "API",
                                "ZXCVB1234N",
                                "Rahul Shah",
                                "9876543210",
                                "rahul@example.com",
                                LocalDate.of(1991, 9, 12),
                                "Delhi",
                                "Delhi",
                                "SELF_EMPLOYED",
                                new BigDecimal("92000.00"),
                                new BigDecimal("45000.00"),
                                12
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
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-020",
                                "API",
                                "ABCDE1234F",
                                "Anika Sharma",
                                "9999999999",
                                "anika@example.com",
                                LocalDate.of(1992, 3, 10),
                                "Mumbai",
                                "Maharashtra",
                                "SALARIED",
                                new BigDecimal("78000.00"),
                                new BigDecimal("45000.00"),
                                12
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void borrowerProfileFieldsAreReturnedInListAndRefreshedOnRepeatedPanIntake() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        createApplication(lsp.id(), product.id(), "EXT-110", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-111",
                                "PARTNER_PORTAL",
                                "ABCDE1234F",
                                "Anika Sharma",
                                "9999999998",
                                "anika.refresh@example.com",
                                LocalDate.of(1992, 3, 10),
                                "Pune",
                                "Maharashtra",
                                "SELF_EMPLOYED",
                                new BigDecimal("91000.00"),
                                new BigDecimal("52000.00"),
                                18
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.borrowerCity").value("Pune"))
                .andExpect(jsonPath("$.borrowerEmploymentType").value("SELF_EMPLOYED"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("q", "pune"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].borrowerCity").value("Pune"))
                .andExpect(jsonPath("$[0].borrowerState").value("Maharashtra"))
                .andExpect(jsonPath("$[0].borrowerEmploymentType").value("SELF_EMPLOYED"))
                .andExpect(jsonPath("$[0].borrowerMonthlyIncome").value(91000.00));
    }

    @Test
    void opsUserCanAssignAndReleaseLoanApplications() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());
        createManagedOpsUser("queue.owner");

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-120", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/assignment", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "assigneeUsername", "queue.owner",
                                "note", "Picked for document review"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedToUsername").value("queue.owner"))
                .andExpect(jsonPath("$.assignedByUsername").value("ops.user"))
                .andExpect(jsonPath("$.assignedAt").exists());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/assignment-events", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].toAssigneeUsername").value("queue.owner"))
                .andExpect(jsonPath("$[0].note").value("Picked for document review"));

        Map<String, Object> releasePayload = new LinkedHashMap<>();
        releasePayload.put("assigneeUsername", null);
        releasePayload.put("note", "Released back to shared queue");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/assignment", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(releasePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedToUsername").doesNotExist())
                .andExpect(jsonPath("$.assignedAt").doesNotExist());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/assignment-events", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromAssigneeUsername").value("queue.owner"))
                .andExpect(jsonPath("$[0].toAssigneeUsername").doesNotExist())
                .andExpect(jsonPath("$[0].note").value("Released back to shared queue"));
    }

    @Test
    void assignmentToUnknownUserIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-121", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/assignment", created.get("id").asText())
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "assigneeUsername", "missing.user",
                                "note", "Assign"
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
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId,
                                productId,
                                externalLoanId,
                                sourceChannel,
                                borrowerPan,
                                "Anika Sharma",
                                "9999999999",
                                "anika@example.com",
                                LocalDate.of(1992, 3, 10),
                                "Mumbai",
                                "Maharashtra",
                                "SALARIED",
                                new BigDecimal("78000.00"),
                                new BigDecimal("45000.00"),
                                12
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode transitionApplication(String applicationId, String targetStatus, String note) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
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

    private void createManagedOpsUser(String username) {
        Set<com.bhawana.lms.domain.AppRole> roles = new LinkedHashSet<>(
                appRoleRepository.findByCodeIn(List.of(com.bhawana.lms.domain.RoleCode.OPS_USER))
        );
        appUserRepository.save(new com.bhawana.lms.domain.AppUser(
                username,
                username + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuv",
                com.bhawana.lms.domain.UserStatus.ACTIVE,
                null,
                roles
        ));
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
