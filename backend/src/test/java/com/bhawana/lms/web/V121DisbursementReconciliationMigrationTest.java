package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.support.TestPanSequence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.bhawana.lms.support.TenantContextTestExecutionListener;

/**
 * H02 V121 — the legacy backfill runs against populated evidence, not an empty database.
 * Covers every stored status, null reference/mode/payload keys, a mismatched V111-style
 * backfilled intent, later terminal history, earliest-stamp first_seen, stranded terminals,
 * and observation immutability. Nothing is invented: missing history stays NULL and routes
 * operator-only with the no-reference account still visible in the queue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class V121DisbursementReconciliationMigrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    @BeforeEach
    void cleanBefore() {
        cleanH02();
    }

    @AfterEach
    void cleanAfter() {
        cleanH02();
    }

    private void cleanH02() {
        jdbcTemplate.execute("TRUNCATE TABLE disbursement_reconciliation_queue");
        jdbcTemplate.execute("TRUNCATE TABLE disbursement_observation");
        loanDisbursementRequestLogRepository.deleteAllInBatch();
        disbursementIntentRepository.deleteAllInBatch();
    }

    @Test
    void backfillMapsEachStoredStatusTruthfully() throws Exception {
        Map<String, UUID> accounts = new LinkedHashMap<>();
        accounts.put("SUCCESS", seedRequestedWithLegacyLog("HDFC0001001", "SUCCESS", "ICI-REF-SUCCESS", true));
        accounts.put("DISBURSED", seedRequestedWithLegacyLog("HDFC0001002", "DISBURSED", "ICI-REF-DISBURSED", true));
        accounts.put("FAILED", seedRequestedWithLegacyLog("HDFC0001003", "FAILED", "ICI-REF-FAILED", true));
        accounts.put("PENDING", seedRequestedWithLegacyLog("HDFC0001004", "PENDING", "ICI-REF-PENDING", true));
        accounts.put("PENDING_RECONCILIATION",
                seedRequestedWithLegacyLog("HDFC0001005", "PENDING_RECONCILIATION", "ICI-REF-PARKED", true));

        runBackfillObservationSection();

        assertObservation(accounts.get("SUCCESS"), "ICI-REF-SUCCESS", "SUCCESS", true, "LEGACY");
        assertObservation(accounts.get("DISBURSED"), "ICI-REF-DISBURSED", "SUCCESS", true, "LEGACY");
        assertObservation(accounts.get("FAILED"), "ICI-REF-FAILED", "FAILED", true, "LEGACY");
        assertObservation(accounts.get("PENDING"), "ICI-REF-PENDING", "PENDING", false, "LEGACY");
        assertObservation(accounts.get("PENDING_RECONCILIATION"), "ICI-REF-PARKED", "PENDING", false, "LEGACY");
    }

    @Test
    void backfillKeepsMissingEvidenceNullAndKeepsNoRefAccountVisible() throws Exception {
        UUID applicationId = seedApproved("HDFC0001006", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        // True legacy shape: the durable intent row postdates this evidence, so remove it —
        // the backfill must cope with stored evidence alone and keep the account visible.
        disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .ifPresent(disbursementIntentRepository::delete);
        // Legacy-shaped stored evidence: no reference, no rail, payload without instruction keys.
        insertLegacyLog(account.getId(), "PENDING", null, null, "{}", "{\"disposition\":\"PENDING\"}");

        runBackfillSections();

        Map<String, Object> obs = latestObservation(account.getId());
        assertNotNull(obs);
        assertNull(obs.get("tran_ref_no"));
        assertNull(obs.get("beneficiary_ifsc"));
        assertNull(obs.get("beneficiary_account_number"));
        assertNull(obs.get("payment_mode"));
        assertEquals("PENDING", obs.get("disposition"));
        assertEquals(false, obs.get("query_resolved"));
        assertEquals("LEGACY", obs.get("provenance"));

        // No fabricated LEGACY- reference anywhere.
        Long fabricated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disbursement_observation WHERE tran_ref_no LIKE 'LEGACY-%'", Long.class);
        assertEquals(0L, fabricated);
        Long unknownPlaceholders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disbursement_observation WHERE beneficiary_ifsc = 'UNKNOWN'", Long.class);
        assertEquals(0L, unknownPlaceholders);

        // The no-reference account stays visible to operators, not omitted.
        Map<String, Object> queue = queueEntry(account.getId());
        assertNotNull(queue);
        assertEquals("LEGACY_MISMATCH", queue.get("reason"));
        assertNull(queue.get("tran_ref_no"));
    }

    @Test
    void backfillFlagsMismatchedBackfilledIntentWithoutBorrowingItsFields() throws Exception {
        UUID applicationId = seedApproved("HDFC0001007", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent liveIntent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        String intentRef = liveIntent.getTranRefNo();
        // Stored latest evidence disagrees with the live (V111-synthesized) intent row.
        insertLegacyLog(account.getId(), "PENDING", "ICI-DIVERGED-1", DisbursementPaymentMode.IMPS,
                payloadJson("ICI-DIVERGED-1", "HDFC0001007", "999988887777", "IMPS"),
                "{\"disposition\":\"PENDING\"}");

        runBackfillSections();

        // Observation carries the actual stored evidence — never the intent's synthesized fields.
        Map<String, Object> obs = latestObservation(account.getId());
        assertNotNull(obs);
        assertEquals("ICI-DIVERGED-1", obs.get("tran_ref_no"));
        assertEquals("HDFC0001007", obs.get("beneficiary_ifsc"));
        assertTrue(!intentRef.equals(obs.get("tran_ref_no")), "expected divergence from live intent ref");

        Map<String, Object> queue = queueEntry(account.getId());
        assertNotNull(queue);
        assertEquals("LEGACY_MISMATCH", queue.get("reason"));
    }

    @Test
    void backfillUsesEarliestStampForFirstSeenAndNeverRewritesIt() throws Exception {
        UUID applicationId = seedApproved("HDFC0001008", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        Instant oldest = Instant.parse("2024-01-05T10:00:00Z");
        Instant newest = Instant.parse("2024-03-05T10:00:00Z");
        insertLegacyLog(account.getId(), "PENDING", "ICI-OLD-1", DisbursementPaymentMode.IMPS,
                payloadJson("ICI-OLD-1", "HDFC0001008", "999988887777", "IMPS"),
                "{\"disposition\":\"PENDING\"}");
        backdateLogs(account.getId(), oldest);
        insertLegacyLog(account.getId(), "PENDING", "ICI-NEW-1", DisbursementPaymentMode.IMPS,
                payloadJson("ICI-NEW-1", "HDFC0001008", "999988887777", "IMPS"),
                "{\"disposition\":\"PENDING\"}");
        jdbcTemplate.update(
                "UPDATE loan_disbursement_request_log SET created_at = ?, updated_at = ? "
                        + "WHERE loan_account_id = ? AND tran_ref_no = ?",
                java.sql.Timestamp.from(newest), java.sql.Timestamp.from(newest),
                account.getId(), "ICI-NEW-1");
        backdateIntent(account.getId(), oldest);

        runBackfillSections();

        Map<String, Object> queue = queueEntry(account.getId());
        assertNotNull(queue);
        Instant firstSeen = ((java.sql.Timestamp) queue.get("first_seen_at")).toInstant();
        assertEquals(oldest, firstSeen);
    }

    @Test
    void laterTerminalHistoryResolvesWithoutQueueRow() throws Exception {
        UUID applicationId = seedApproved("HDFC0001009", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        insertLegacyLog(account.getId(), "PENDING", "ICI-HIST-1", DisbursementPaymentMode.IMPS,
                payloadJson("ICI-HIST-1", "HDFC0001009", "999988887777", "IMPS"),
                "{\"disposition\":\"PENDING\"}");
        backdateLogs(account.getId(), Instant.parse("2024-01-05T10:00:00Z"));
        insertLegacyLog(account.getId(), "DISBURSED", "ICI-HIST-2", DisbursementPaymentMode.IMPS,
                payloadJson("ICI-HIST-2", "HDFC0001009", "999988887777", "IMPS"),
                "{\"disposition\":\"SUCCESS\"}");
        jdbcTemplate.update("UPDATE loan_account SET status = 'DISBURSED' WHERE id = ?", account.getId());

        runBackfillSections();

        // Only the actual latest evidence is backfilled, mapped to definitive SUCCESS.
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disbursement_observation WHERE loan_account_id = ?", Long.class,
                account.getId()));
        assertObservation(account.getId(), "ICI-HIST-2", "SUCCESS", true, "LEGACY");
        assertNull(queueEntry(account.getId()));
    }

    @Test
    void strandedTerminalIsQueuedForRepairPath() throws Exception {
        UUID applicationId = seedApproved("HDFC0001010", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent liveIntent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        insertLegacyLog(account.getId(), "DISBURSED", liveIntent.getTranRefNo(), liveIntent.getPaymentMode(),
                payloadJson(liveIntent.getTranRefNo(), liveIntent.getBeneficiaryIfsc(),
                        liveIntent.getBeneficiaryAccountNumber(), liveIntent.getPaymentMode().name()),
                "{\"disposition\":\"SUCCESS\"}");
        // Stored terminal evidence applied to the intent row, loan still REQUESTED (C02 repair path).
        DisbursementIntent stored =
                disbursementIntentRepository.findById(liveIntent.getId()).orElseThrow();
        stored.recordProviderResponse(DisbursementIntentState.SUCCEEDED, liveIntent.getTranRefNo(),
                "0", "RRN-V121-STRANDED", DisbursementDeclineKind.NONE);
        disbursementIntentRepository.save(stored);

        runBackfillSections();

        Map<String, Object> queue = queueEntry(account.getId());
        assertNotNull(queue);
        assertEquals("STRANDED_TERMINAL", queue.get("reason"));
        assertEquals(liveIntent.getTranRefNo(), queue.get("tran_ref_no"));
    }

    @Test
    void observationsRejectMutation() {
        UUID accountId = jdbcTemplate
                .query("SELECT id FROM loan_account LIMIT 1",
                        (rs, i) -> (UUID) rs.getObject("id"))
                .stream().findFirst().orElseGet(() -> {
                    // No accounts exist in this fresh database; seed one so the trigger is
                    // exercised against a real row rather than skipped.
                    try {
                        UUID applicationId = seedApproved("HDFC0001011", new BigDecimal("45000.00"));
                        return loanAccountRepository.findByLoanApplication_Id(applicationId)
                                .orElseThrow().getId();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        jdbcTemplate.update(
                "INSERT INTO disbursement_observation (id, loan_account_id, tran_ref_no, kind, disposition, "
                        + "query_resolved, provider_name, request_payload_json, response_payload_json, "
                        + "provenance, created_by) VALUES (?, ?, 'ICI-IMMUT', 'LEGACY', 'PENDING', false, "
                        + "'TEST', '{}', '{}', 'LEGACY', 'test')",
                UUID.randomUUID(), accountId);
        UUID finalAccountId = accountId;
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE disbursement_observation SET disposition = 'SUCCESS' WHERE loan_account_id = ?",
                finalAccountId));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "DELETE FROM disbursement_observation WHERE loan_account_id = ?", finalAccountId));
    }

    // --- backfill runner: executes the actual V121 backfill sections, not a copy ---

    private void runBackfillSections() {
        runBackfillObservationSection();
        runBackfillQueueSection();
    }

    private void runBackfillObservationSection() {
        executeMigrationSection("H02-BACKFILL-OBSERVATION");
    }

    private void runBackfillQueueSection() {
        executeMigrationSection("H02-BACKFILL-QUEUE");
    }

    private void executeMigrationSection(String marker) {
        String sql;
        try (java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V121__disbursement_reconciliation.sql")) {
            assertNotNull(in, "V121 migration must be on the test classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        String start = "-- " + marker + "-START";
        String end = "-- " + marker + "-END";
        int from = sql.indexOf(start);
        int to = sql.indexOf(end);
        assertTrue(from >= 0 && to > from, "migration must contain " + marker + " section");
        String section = sql.substring(from + start.length(), to);
        // Strip full-line SQL comments BEFORE splitting: header prose contains semicolons
        // (e.g. "-- NULL; ...") that must never act as statement terminators.
        StringBuilder stripped = new StringBuilder();
        for (String line : section.split("\n")) {
            if (!line.trim().startsWith("--")) {
                stripped.append(line).append('\n');
            }
        }
        for (String statement : splitStatements(stripped.toString())) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }
    }

    /**
     * Splits a SQL script on semicolons that terminate statements, ignoring semicolons inside
     * full-line comments (already stripped) and inside single-quoted string literals — the H02
     * backfill details prose contains both.
     */
    private static List<String> splitStatements(String script) {
        List<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
                current.append(c);
                continue;
            }
            if (c == ';' && !inString) {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().trim().isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    // --- assertions ---

    private void assertObservation(UUID accountId, String expectedRef, String expectedDisposition,
            boolean expectedResolved, String expectedProvenance) {
        Map<String, Object> obs = latestObservation(accountId);
        assertNotNull(obs, "expected a backfilled observation for account " + accountId);
        assertEquals(expectedRef, obs.get("tran_ref_no"));
        assertEquals(expectedDisposition, obs.get("disposition"));
        assertEquals(expectedResolved, obs.get("query_resolved"));
        assertEquals(expectedProvenance, obs.get("provenance"));
        assertEquals(false, obs.get("is_duplicate"));
    }

    private Map<String, Object> latestObservation(UUID accountId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM disbursement_observation WHERE loan_account_id = ? "
                        + "ORDER BY observed_at DESC LIMIT 1",
                accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> queueEntry(UUID accountId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM disbursement_reconciliation_queue WHERE loan_account_id = ?", accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // --- fixtures ---

    private UUID seedRequestedWithLegacyLog(String ifsc, String providerStatus, String ref,
            boolean withInstructionKeys) throws Exception {
        UUID applicationId = seedApproved(ifsc, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String payload = withInstructionKeys
                ? payloadJson(ref, ifsc, "999988887777", "IMPS")
                : "{}";
        insertLegacyLog(account.getId(), providerStatus, ref, DisbursementPaymentMode.IMPS, payload,
                "{\"disposition\":\"" + providerStatus + "\"}");
        return account.getId();
    }

    private void insertLegacyLog(UUID accountId, String providerStatus, String ref,
            DisbursementPaymentMode mode, String requestPayload, String responsePayload) {
        LoanAccount account = loanAccountRepository.findById(accountId).orElseThrow();
        LoanDisbursementRequestLog log = new LoanDisbursementRequestLog(
                account,
                "legacy.fixture",
                new BigDecimal("44000.00"),
                "MOCK_ICICI",
                ref == null ? "REQ-LEGACY" : ref,
                providerStatus,
                mode,
                ref,
                "11",
                null,
                DisbursementDeclineKind.NONE,
                requestPayload,
                responsePayload,
                "corr-legacy");
        loanDisbursementRequestLogRepository.saveAndFlush(log);
    }

    private void backdateLogs(UUID accountId, Instant at) {
        java.sql.Timestamp stamp = java.sql.Timestamp.from(at);
        jdbcTemplate.update(
                "UPDATE loan_disbursement_request_log SET created_at = ?, updated_at = ? WHERE loan_account_id = ?",
                stamp, stamp, accountId);
    }

    private void backdateIntent(UUID accountId, Instant at) {
        java.sql.Timestamp stamp = java.sql.Timestamp.from(at);
        jdbcTemplate.update(
                "UPDATE disbursement_intent SET created_at = ?, updated_at = ? WHERE loan_account_id = ?",
                stamp, stamp, accountId);
    }

    private String payloadJson(String ref, String ifsc, String beneficiaryAccount, String mode) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "beneficiaryIfsc", ifsc,
                    "beneficiaryAccountNumber", beneficiaryAccount,
                    "tranRefNo", ref,
                    "paymentMode", mode));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for H02 test");
        seedBorrowerBankDetails(applicationId, ifsc);
        return UUID.fromString(applicationId);
    }

    private void seedBorrowerBankDetails(String applicationId, String ifsc) throws Exception {
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "H02 Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "H02 Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "H02 LSP",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createProductViaAdmin() throws Exception {
        String code = "PROD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "H02 product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("1000000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private String createApplicationViaOps(String lspId, String productId, BigDecimal requestedAmount) throws Exception {
        String borrowerPan = TestPanSequence.uniquePan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "H02 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "h02+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 1));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("250000.00"));
        payload.put("requestedAmount", requestedAmount);
        payload.put("tenureMonths", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void transition(String applicationId, String targetStatus, String note) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
                .andExpect(status().isOk());
    }

    private void markKycComplete(String applicationId) {
        UUID applicationUuid = UUID.fromString(applicationId);
        loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationUuid)
                .forEach(item -> {
                    if (!item.isRequired()) {
                        return;
                    }
                    String documentKey = item.getDocumentType().name().toLowerCase();
                    item.update(
                            LoanApplicationDocumentChecklistStatus.SUBMITTED,
                            "Uploaded for H02 test",
                            "ops.user",
                            documentKey + ".pdf",
                            "storage://" + applicationId + "/" + documentKey + ".pdf",
                            null,
                            "application/pdf",
                            1024L,
                            "checksum-" + documentKey,
                            "storage-key/" + applicationId + "/" + documentKey,
                            true
                    );
                    loanApplicationDocumentChecklistRepository.save(item);
                });
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        return "9" + String.format("%09d", hash % 1_000_000_000);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(token -> token.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
