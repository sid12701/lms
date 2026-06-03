package com.bhawana.lms.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAudit;
import com.bhawana.lms.domain.LoanApplicationDocumentAccessAuditAction;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductAuditAction;
import com.bhawana.lms.domain.LoanProductAuditEvent;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentAccessAuditRepository;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditExplorerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationAuditEventRepository applicationAuditRepo;

    @Autowired
    private LoanApplicationIntakeAuditRepository intakeAuditRepo;

    @Autowired
    private LoanApplicationDocumentAccessAuditRepository documentAccessRepo;

    @Autowired
    private LoanProductAuditEventRepository productAuditRepo;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Autowired
    private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;

    @Autowired
    private LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private com.bhawana.lms.repo.LoanDisbursementBankMismatchLogRepository loanDisbursementBankMismatchLogRepository;

    @Autowired
    private com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository borrowerBankDetailsUpdateAuditRepository;

    @Autowired
    private com.bhawana.lms.repo.DisbursementOutcomeAuditRepository disbursementOutcomeAuditRepository;

    private Lsp lspA;
    private Lsp lspB;
    private LoanProduct product;
    private Borrower borrowerA;
    private Borrower borrowerB;
    private LoanApplication applicationA;
    private LoanApplication applicationB;

    @BeforeEach
    void setUp() {
        cleanAll();
        seed();
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    private void cleanAll() {
        // Clear in dependency order so neighbouring tests can blow away
        // shared parent tables (LSP, product) without tripping FKs on stale
        // loan_application / audit rows we leave behind. LSPs themselves are
        // *not* cleared here because app_user.lsp_id (seeded by other test
        // classes) would block the delete — leftover LSPs are harmless since
        // every seed() uses random LSP codes.
        disbursementOutcomeAuditRepository.deleteAllInBatch();
        loanDisbursementBankMismatchLogRepository.deleteAllInBatch();
        borrowerBankDetailsUpdateAuditRepository.deleteAllInBatch();
        loanPaymentTransactionRepository.deleteAllInBatch();
        loanDisbursementRequestLogRepository.deleteAllInBatch();
        loanRepaymentScheduleInstallmentRepository.deleteAllInBatch();
        loanForeclosureQuoteRepository.deleteAllInBatch();
        loanAccountRepository.deleteAllInBatch();
        applicationAuditRepo.deleteAllInBatch();
        intakeAuditRepo.deleteAllInBatch();
        documentAccessRepo.deleteAllInBatch();
        loanApplicationAssignmentEventRepository.deleteAllInBatch();
        loanApplicationDocumentChecklistRepository.deleteAllInBatch();
        loanApplicationStatusTransitionRepository.deleteAllInBatch();
        loanApplicationRepository.deleteAllInBatch();
        borrowerRepository.deleteAllInBatch();
        productAuditRepo.deleteAllInBatch();
        loanProductLspMappingRepository.deleteAllInBatch();
        loanProductRepository.deleteAllInBatch();
    }

    private void seed() {

        lspA = lspRepository.save(new Lsp("AUDIT-A-" + suffix(), "Audit LSP A", LspStatus.ACTIVE));
        lspB = lspRepository.save(new Lsp("AUDIT-B-" + suffix(), "Audit LSP B", LspStatus.ACTIVE));
        product = loanProductRepository.save(new LoanProduct(
                "AUDIT-PROD-" + suffix(),
                "Audit Product",
                new BigDecimal("5000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("18.00"),
                new BigDecimal("2.00"),
                6,
                24,
                LoanProductStatus.ACTIVE
        ));

        borrowerA = borrowerRepository.save(new Borrower("Audit Alpha", randomPan(), "9000000001", "alpha-" + suffix() + "@example.com"));
        borrowerB = borrowerRepository.save(new Borrower("Audit Beta", randomPan(), "9000000002", "beta-" + suffix() + "@example.com"));

        applicationA = loanApplicationRepository.save(new LoanApplication(
                borrowerA,
                lspA,
                product,
                "EXT-AUDIT-A-" + suffix(),
                "API",
                new BigDecimal("50000.00"),
                12,
                LoanApplicationStatus.INITIALIZED
        ));
        applicationB = loanApplicationRepository.save(new LoanApplication(
                borrowerB,
                lspB,
                product,
                "EXT-AUDIT-B-" + suffix(),
                "API",
                new BigDecimal("75000.00"),
                12,
                LoanApplicationStatus.INITIALIZED
        ));

        // Seed one row per audit stream tied to applicationA + lspA.
        applicationAuditRepo.save(new LoanApplicationAuditEvent(
                applicationA,
                LoanApplicationAuditAction.STATUS_TRANSITION,
                "alice.ops",
                LoanApplicationStatus.INITIALIZED,
                LoanApplicationStatus.AWAITING_APPROVAL,
                "Routine forward transition",
                null,
                "corr-app-1"
        ));

        intakeAuditRepo.save(new LoanApplicationIntakeAudit(
                applicationA,
                "intake.bot",
                "corr-intake-1",
                "{\"borrowerFullName\":\"Audit Alpha\",\"borrowerAadharNumber\":\"123456789012\",\"borrowerPan\":\"AAAPA1234A\"}"
        ));

        documentAccessRepo.save(new LoanApplicationDocumentAccessAudit(
                applicationA,
                LoanApplicationDocumentAccessAuditAction.CHECKLIST_VIEWED,
                "alice.ops",
                "Checklist viewed",
                List.of(LoanApplicationDocumentType.PAN_CARD, LoanApplicationDocumentType.AADHAAR_FILE),
                "corr-doc-1"
        ));

        productAuditRepo.save(new LoanProductAuditEvent(
                product,
                LoanProductAuditAction.PRODUCT_UPDATED,
                "prod.admin",
                "Rate changed from 17 to 18",
                "corr-prod-1"
        ));

        // Seed an unrelated APPLICATION row on applicationB so we can verify
        // lspId / loanApplicationId filter pushdown excludes it.
        applicationAuditRepo.save(new LoanApplicationAuditEvent(
                applicationB,
                LoanApplicationAuditAction.STATUS_TRANSITION,
                "bob.ops",
                LoanApplicationStatus.INITIALIZED,
                LoanApplicationStatus.AWAITING_APPROVAL,
                "Other tenant transition",
                null,
                "corr-app-2"
        ));
    }

    @Test
    void searchReturnsAllFourStreamsForSystemAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "APPLICATION,INTAKE,DOCUMENT_ACCESS,PRODUCT")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.items[*].stream", hasItem("APPLICATION")))
                .andExpect(jsonPath("$.items[*].stream", hasItem("INTAKE")))
                .andExpect(jsonPath("$.items[*].stream", hasItem("DOCUMENT_ACCESS")))
                .andExpect(jsonPath("$.items[*].stream", hasItem("PRODUCT")));
    }

    @Test
    void searchIsForbiddenForOpsUser() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events").with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchIsForbiddenForLspApiClient() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events").with(lspApiClient()))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdminCanCountDocumentAccessAuditsByDocumentType() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events/document-access/document-type-counts")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.documentType == 'PAN_CARD')].count", hasItem(1)))
                .andExpect(jsonPath("$[?(@.documentType == 'AADHAAR_FILE')].count", hasItem(1)));
    }

    @Test
    void streamsFilterScopesResponseToSelectedStreams() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "INTAKE,PRODUCT")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].stream", hasItem("INTAKE")))
                .andExpect(jsonPath("$.items[*].stream", hasItem("PRODUCT")))
                .andExpect(jsonPath("$.items[*].stream", not(hasItem("APPLICATION"))))
                .andExpect(jsonPath("$.items[*].stream", not(hasItem("DOCUMENT_ACCESS"))));
    }

    @Test
    void actorUsernameFilterReturnsOnlyMatchingRows() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("actorUsername", "alice.ops")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[*].actorUsername", hasItem("alice.ops")))
                .andExpect(jsonPath("$.items[*].actorUsername", not(hasItem("bob.ops"))))
                .andExpect(jsonPath("$.items[*].actorUsername", not(hasItem("intake.bot"))));
    }

    @Test
    void lspIdFilterExcludesProductStreamAndOtherLsp() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("lspId", lspA.getId().toString())
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                // applicationA's APPLICATION/INTAKE/DOCUMENT_ACCESS rows only.
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[*].stream", not(hasItem("PRODUCT"))))
                .andExpect(jsonPath("$.items[*].lspId",
                        hasItem(lspA.getId().toString())))
                .andExpect(jsonPath("$.items[*].lspId",
                        not(hasItem(lspB.getId().toString()))));
    }

    @Test
    void loanApplicationIdFilterPushesDownToEveryBranch() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("loanApplicationId", applicationA.getId().toString())
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[*].loanApplicationId",
                        hasItem(applicationA.getId().toString())))
                .andExpect(jsonPath("$.items[*].loanApplicationId",
                        not(hasItem(applicationB.getId().toString()))));
    }

    @Test
    void productIdFilterReturnsOnlyProductStream() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("productId", product.getId().toString())
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].stream").value("PRODUCT"))
                .andExpect(jsonPath("$.items[0].productId").value(product.getId().toString()));
    }

    @Test
    void intakePayloadAadhaarIsMaskedInDetail() throws Exception {
        String response = mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "INTAKE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].stream").value("INTAKE"))
                .andExpect(jsonPath("$.items[0].detail.payload.borrowerAadharNumber",
                        equalTo("XXXXXXXX9012")))
                .andReturn().getResponse().getContentAsString();
        assertEquals(false, response.contains("123456789012"), "raw aadhaar must not leak through the response");
    }

    @Test
    void compositeIdFormatIsStreamColonNativeId() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "PRODUCT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id",
                        containsString("PRODUCT:")));
    }

    @Test
    void paginationDetailsOffByDefaultReturnsSentinel() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events").with(systemAdmin()))
                .andExpect(status().isOk())
                // totalCount is the -1 sentinel when paginationDetails=false.
                .andExpect(jsonPath("$.totalCount").value(-1));
    }

    @Test
    void offsetLimitClampsToBoundsAndPaginates() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("limit", "2")
                        .queryParam("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(0));

        // limit above the 500 cap is clamped, not rejected.
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("limit", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(500));
    }

    @Test
    void invalidSinceParameterReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("since", "not-an-instant"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownStreamValueReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "FOO_BAR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseIsOrderedByOccurredAtDesc() throws Exception {
        String body = mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
        com.fasterxml.jackson.databind.JsonNode items = root.get("items");
        for (int i = 1; i < items.size(); i++) {
            String prev = items.get(i - 1).get("occurredAt").asText();
            String curr = items.get(i).get("occurredAt").asText();
            // Strings are ISO-8601 and lexicographically comparable.
            assertEquals(true, prev.compareTo(curr) >= 0,
                    "expected occurredAt to be non-increasing; got " + prev + " -> " + curr);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String randomPan() {
        String hex = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        // PAN shape: 5 letters + 4 digits + 1 letter. Map hex chars onto the
        // alphabet (A-P) and decimal digits.
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            char c = hex.charAt(i);
            // hex chars are [0-9A-F]; map to A-P (16 letters) so all are valid.
            int idx;
            if (c >= '0' && c <= '9') {
                idx = c - '0';
            } else {
                idx = 10 + (c - 'A');
            }
            letters.append((char) ('A' + idx));
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 5; i < 9; i++) {
            char c = hex.charAt(i);
            int idx = (c >= '0' && c <= '9') ? (c - '0') : (10 + (c - 'A'));
            digits.append((char) ('0' + (idx % 10)));
        }
        char lastLetter;
        char tail = hex.charAt(9);
        if (tail >= '0' && tail <= '9') {
            lastLetter = (char) ('A' + (tail - '0'));
        } else {
            lastLetter = (char) ('A' + 10 + (tail - 'A'));
        }
        return letters + digits.toString() + lastLetter;
    }

    private static JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private static JwtRequestPostProcessor lspApiClient() {
        return jwt().jwt(token -> token.subject("client.api").claim("roles", List.of("LSP_API_CLIENT")))
                .authorities(() -> "ROLE_LSP_API_CLIENT");
    }
}
