package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository;
import com.bhawana.lms.repo.BorrowerLspRelationshipRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.service.BorrowerBankDetailsService;
import com.bhawana.lms.service.BorrowerBankDetailsService.BorrowerBankDetailsCommand;
import com.bhawana.lms.service.BorrowerBankUpdatePolicy;
import com.bhawana.lms.service.BorrowerOnboardingService;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanApplicationOnboardingCommand;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The shared borrower bank instruction is stable across LSPs.
 *
 * <p>Two LSPs onboard the same global borrower (same PAN/mobile) before either loan is
 * approved. Approving and initiating A's disbursement freezes A's instruction; B's edit must
 * then be rejected atomically (no borrower, access or audit delta). Both commit orderings are
 * covered, plus a true concurrent approval-vs-edit race, failed-onboarding rollback, audit
 * failure rollback, a permitted edit after terminal evidence, and a negative tenant-scope
 * probe. Frozen-snapshot polling is left intact and exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class CrossLspBankRaceIntegrationTest {

    private static final String FROZEN_IFSC = "MOCK0PENDOK";
    private static final String EDITED_IFSC = "MOCK0PENDFL";
    private static final String TERMINAL_IFSC = "HDFC0008888";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private BorrowerRepository borrowerRepository;
    @Autowired private BorrowerLspRelationshipRepository borrowerLspRelationshipRepository;
    @Autowired private BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanApplicationLifecycleService loanApplicationLifecycleService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private BorrowerBankDetailsService borrowerBankDetailsService;
    @Autowired private BorrowerBankUpdatePolicy borrowerBankUpdatePolicy;
    @Autowired private BorrowerOnboardingService borrowerOnboardingService;
    @Autowired private LspRepository lspRepository;
    @Autowired private OpsAlertRepository opsAlertRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void approvalThenEditBlockedAndFrozenInstructionSurvives() throws Exception {
        Pair pair = setupSharedBorrowerPair("AE");
        approve(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        long auditsBefore = auditCount(pair.borrowerId());
        int relationshipsBefore = relationshipCount(pair.borrowerId());
        var visibilityBefore = visibleLspIds(pair.borrowerId());
        String nameBefore = fullName(pair.borrowerId());
        initiate(pair.appAId());

        // B's edit lands after A's live intent exists: globally blocked, atomically.
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", pair.borrowerId())
                        .header("Authorization", "Bearer " + pair.tokenB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "667788990011",
                                "bankName", "B Bank",
                                "ifscCode", EDITED_IFSC,
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT"));

        assertEquals(FROZEN_IFSC, liveIfsc(pair.borrowerId()));
        assertEquals(auditsBefore, auditCount(pair.borrowerId()));
        assertEquals(relationshipsBefore, relationshipCount(pair.borrowerId()));
        assertEquals(visibilityBefore, visibleLspIds(pair.borrowerId()));
        assertEquals(nameBefore, fullName(pair.borrowerId()));
        DisbursementIntent intent = liveIntent(pair.appAId());
        assertEquals(FROZEN_IFSC, intent.getBeneficiaryIfsc());

        // Phase-1 polling still reconciles on the frozen instruction despite the blocked edit.
        UUID appA = UUID.fromString(pair.appAId());
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(appA, "worker", null, "c06p2-ae-1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(appA, "worker", null, "c06p2-ae-2"));
        assertEquals(LoanAccountStatus.DISBURSED, accountStatus(pair.appAId()));
    }

    @Test
    void editThenApprovalFreezesEditedInstructionAndBlocksLaterEdits() throws Exception {
        Pair pair = setupSharedBorrowerPair("EA");
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);

        // Permitted pre-flight edit from B: no live instruction anywhere yet.
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", pair.borrowerId())
                        .header("Authorization", "Bearer " + pair.tokenB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "112233445566",
                                "bankName", "B Bank",
                                "ifscCode", EDITED_IFSC,
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ifscCode").value(EDITED_IFSC));
        long auditsAfterEdit = auditCount(pair.borrowerId());
        assertTrue(auditsAfterEdit >= 1);

        approve(pair.appAId());
        initiate(pair.appAId());
        assertEquals(EDITED_IFSC, liveIntent(pair.appAId()).getBeneficiaryIfsc());

        // A second B edit is now frozen out and leaves no delta.
        int relationshipsBefore = relationshipCount(pair.borrowerId());
        var visibilityBefore = visibleLspIds(pair.borrowerId());
        String nameBefore = fullName(pair.borrowerId());
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", pair.borrowerId())
                        .header("Authorization", "Bearer " + pair.tokenB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "999900001111",
                                "bankName", "B Bank",
                                "ifscCode", TERMINAL_IFSC,
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT"));
        assertEquals(EDITED_IFSC, liveIfsc(pair.borrowerId()));
        assertEquals(auditsAfterEdit, auditCount(pair.borrowerId()));
        assertEquals(relationshipsBefore, relationshipCount(pair.borrowerId()));
        assertEquals(visibilityBefore, visibleLspIds(pair.borrowerId()));
        assertEquals(nameBefore, fullName(pair.borrowerId()));
        assertEquals(EDITED_IFSC, liveIntent(pair.appAId()).getBeneficiaryIfsc());
    }

    @Test
    void concurrentApprovalAndEditStayMutuallyConsistent() throws Exception {
        Pair pair = setupSharedBorrowerPair("CC");
        approve(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        long auditsBefore = auditCount(pair.borrowerId());

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        UUID appA = UUID.fromString(pair.appAId());
        UUID borrowerId = UUID.fromString(pair.borrowerId());
        UUID lspB = UUID.fromString(pair.lspBId());
        try {
            Future<?> initiation = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                await(gate);
                return loanDisbursementCommandService.initiateDisbursement(appA, "c06p2-race");
            }));
            Future<?> edit = pool.submit(() -> TenantScopedExecution.callAsTenant(lspB, () -> {
                await(gate);
                return borrowerBankDetailsService.updateBankDetailsForLsp(
                        lspB,
                        borrowerId,
                        new BorrowerBankDetailsCommand(
                                "343434343434", "B Bank", EDITED_IFSC, "Shared Borrower"),
                        "b.client",
                        "127.0.0.1");
            }));
            gate.countDown();
            initiation.get(60, TimeUnit.SECONDS);
            boolean blocked = false;
            try {
                edit.get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failed) {
                assertTrue(failed.getCause() instanceof BusinessRuleViolationException);
                assertEquals("BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT",
                        ((BusinessRuleViolationException) failed.getCause()).getErrorCode());
                blocked = true;
            }

            String finalIfsc = liveIfsc(pair.borrowerId());
            long auditsAfter = auditCount(pair.borrowerId());
            String snapshotIfsc = liveIntent(pair.appAId()).getBeneficiaryIfsc();
            if (blocked) {
                // Initiation won: the edit left no trace and the snapshot predates it.
                assertEquals(FROZEN_IFSC, finalIfsc);
                assertEquals(auditsBefore, auditsAfter);
                assertEquals(FROZEN_IFSC, snapshotIfsc);
            } else {
                // Edit won: initiation snapshotted the edited instruction, audited once.
                assertEquals(EDITED_IFSC, finalIfsc);
                assertEquals(auditsBefore + 1, auditsAfter);
                assertEquals(EDITED_IFSC, snapshotIfsc);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentApprovalAndOnboardingStayMutuallyConsistent() throws Exception {
        Pair pair = setupSharedBorrowerPair("AO");
        transition(pair.appAId(), "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(pair.appAId());
        long applicationsBefore = loanApplicationRepository.count();
        long auditsBefore = auditCount(pair.borrowerId());
        int relationshipsBefore = relationshipCount(pair.borrowerId());
        var visibilityBefore = visibleLspIds(pair.borrowerId());
        String bankBefore = liveAccount(pair.borrowerId());

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        UUID appA = UUID.fromString(pair.appAId());
        try {
            Future<?> approval = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                await(gate);
                return loanApplicationLifecycleService.transitionStatus(
                        appA,
                        "c06p2-race",
                        LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                        "Race approval",
                        null);
            }));
            Future<MvcResult> onboarding = pool.submit(() -> {
                await(gate);
                return mockMvc.perform(post("/api/v1/lsp/loan-applications")
                                .header("Authorization", "Bearer " + pair.tokenB())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        externalPayload(pair.lspBId(), pair.productId(),
                                                "EXT-C06P2-AO-B2", pair.pan()))))
                        .andReturn();
            });
            gate.countDown();
            approval.get(60, TimeUnit.SECONDS);
            MvcResult onboardingResult = onboarding.get(60, TimeUnit.SECONDS);
            int onboardingStatus = onboardingResult.getResponse().getStatus();

            if (onboardingStatus == 409) {
                assertEquals("BORROWER_HAS_ACTIVE_LOAN",
                        objectMapper.readTree(onboardingResult.getResponse().getContentAsString()).get("code").asText());
                assertEquals(applicationsBefore, loanApplicationRepository.count());
                assertEquals(bankBefore, liveAccount(pair.borrowerId()));
                assertEquals(auditsBefore, auditCount(pair.borrowerId()));
                assertEquals(relationshipsBefore, relationshipCount(pair.borrowerId()));
                assertEquals(visibilityBefore, visibleLspIds(pair.borrowerId()));
            } else {
                assertEquals(200, onboardingStatus);
                assertEquals(applicationsBefore + 1, loanApplicationRepository.count());
                assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                        loanApplicationRepository.findById(appA).orElseThrow().getStatus());
                // Same-PAN second onboarding changed nothing observable: identical payload values.
                assertEquals(bankBefore, liveAccount(pair.borrowerId()));
                assertEquals(auditsBefore, auditCount(pair.borrowerId()));
                assertEquals(relationshipsBefore, relationshipCount(pair.borrowerId()));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedOnboardingLeavesNoVisibilityProfileOrAuditDelta() throws Exception {
        Pair pair = setupSharedBorrowerPair("FO");
        approve(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        String bankBefore = liveAccount(pair.borrowerId());
        String nameBefore = fullName(pair.borrowerId());
        long auditsBefore = auditCount(pair.borrowerId());
        int relationshipsBefore = relationshipCount(pair.borrowerId());
        var visibilityBefore = visibleLspIds(pair.borrowerId());

        // B already holds visibility; a third LSP must fail the open-loan guard atomically.
        String lspC = createLspViaAdmin("C06P2-FO-C");
        String productC = createProductViaAdmin();
        mapProductToLsp(productC, lspC);
        JsonNode clientC = createApiClient(lspC);
        String tokenC = issueToken(clientC);

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + tokenC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                externalPayload(lspC, productC, "EXT-C06P2-FO", pair.pan()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BORROWER_HAS_ACTIVE_LOAN"));

        assertFalse(borrowerRepository.findById(UUID.fromString(pair.borrowerId())).orElseThrow()
                .hasVisibilityFor(UUID.fromString(lspC)));
        assertEquals(bankBefore, liveAccount(pair.borrowerId()));
        assertEquals(nameBefore, fullName(pair.borrowerId()));
        assertEquals(FROZEN_IFSC, liveIfsc(pair.borrowerId()));
        assertEquals(auditsBefore, auditCount(pair.borrowerId()));
        assertEquals(relationshipsBefore, relationshipCount(pair.borrowerId()));
        assertEquals(visibilityBefore, visibleLspIds(pair.borrowerId()));
    }

    @Test
    void auditFailureRollsBackBankMutation() throws Exception {
        Pair pair = setupSharedBorrowerPair("AF");
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        String bankBefore = liveAccount(pair.borrowerId());
        long auditsBefore = auditCount(pair.borrowerId());

        UUID lspB = UUID.fromString(pair.lspBId());
        UUID borrowerId = UUID.fromString(pair.borrowerId());
        assertThrows(RuntimeException.class, () -> TenantScopedExecution.callAsTenant(lspB, () ->
                borrowerBankDetailsService.updateBankDetailsForLsp(
                        lspB,
                        borrowerId,
                        new BorrowerBankDetailsCommand("9".repeat(65), "B Bank", EDITED_IFSC, "Shared Borrower"),
                        "b.client",
                        "127.0.0.1")));

        assertEquals(bankBefore, liveAccount(pair.borrowerId()));
        assertEquals(FROZEN_IFSC, liveIfsc(pair.borrowerId()));
        assertEquals(auditsBefore, auditCount(pair.borrowerId()));
    }

    @Test
    void permittedEditAfterTerminalEvidenceAndNegativeTenantScope() throws Exception {
        Pair pair = setupSharedBorrowerPair("PT");
        approve(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        initiate(pair.appAId());
        UUID appA = UUID.fromString(pair.appAId());
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(appA, "worker", null, "c06p2-pt-1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(appA, "worker", null, "c06p2-pt-2"));
        assertEquals(LoanAccountStatus.DISBURSED, accountStatus(pair.appAId()));
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(UUID.fromString(pair.appAId())).orElseThrow().getStatus());

        // Terminal evidence exists and no live intent remains: B's edit is permitted + audited.
        long auditsBefore = auditCount(pair.borrowerId());
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", pair.borrowerId())
                        .header("Authorization", "Bearer " + pair.tokenB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "787878787878",
                                "bankName", "B Bank",
                                "ifscCode", TERMINAL_IFSC,
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ifscCode").value(TERMINAL_IFSC));
        assertEquals(auditsBefore + 1, auditCount(pair.borrowerId()));

        // An LSP without visibility learns nothing and changes nothing.
        String lspC = createLspViaAdmin("C06P2-PT-C");
        JsonNode clientC = createApiClient(lspC);
        String tokenC = issueToken(clientC);
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", pair.borrowerId())
                        .header("Authorization", "Bearer " + tokenC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "121212121212",
                                "bankName", "C Bank",
                                "ifscCode", EDITED_IFSC,
                                "accountHolderName", "Stranger"
                        ))))
                .andExpect(status().isNotFound());
        assertEquals(TERMINAL_IFSC, liveIfsc(pair.borrowerId()));
        assertEquals(auditsBefore + 1, auditCount(pair.borrowerId()));
    }

    @Test
    void identicalResubmissionIsSuccessfulNoWrite() throws Exception {
        Pair pair = setupSharedBorrowerPair("NO");
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        long auditsBefore = auditCount(pair.borrowerId());

        // Normalized-identical values are an intentional successful no-write: 200, same
        // payload, no new audit row, no profile touch. There is no rejection for no-ops.
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", pair.borrowerId())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "Test Bank",
                                "ifscCode", FROZEN_IFSC.toLowerCase(),
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ifscCode").value(FROZEN_IFSC));
        assertEquals(auditsBefore, auditCount(pair.borrowerId()));
        assertEquals(FROZEN_IFSC, liveIfsc(pair.borrowerId()));
    }

    @Test
    void velocityThresholdIdenticalForAdminAndTenant() throws Exception {
        // The admin seed is itself the first audited update, so the threshold (2) is reached by
        // exactly one further update on either scope — and a lone seed stays below it.
        Pair adminPair = setupSharedBorrowerPair("VA");
        seedBankDetails(adminPair.borrowerId(), FROZEN_IFSC);
        assertFalse(hasVelocityAlert(adminPair.borrowerId()),
                "A single bank update must stay below the velocity threshold");
        adminBankEdit(adminPair.borrowerId(), "111111111111");
        assertTrue(hasVelocityAlert(adminPair.borrowerId()),
                "Two admin bank updates must trip the velocity alert");

        Pair tenantPair = setupSharedBorrowerPair("VT");
        seedBankDetails(tenantPair.borrowerId(), FROZEN_IFSC);
        assertFalse(hasVelocityAlert(tenantPair.borrowerId()),
                "A single bank update must stay below the velocity threshold");
        tenantBankEdit(tenantPair, "111111111111");
        assertTrue(hasVelocityAlert(tenantPair.borrowerId()),
                "Two tenant bank updates must trip the velocity alert at the same threshold");
    }

    @Test
    void heldEditWinsApprovalSnapshotsCommittedEdit() throws Exception {
        Pair pair = setupSharedBorrowerPair("HE");
        transition(pair.appAId(), "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        long auditsBefore = auditCount(pair.borrowerId());
        UUID borrowerId = UUID.fromString(pair.borrowerId());
        UUID lspB = UUID.fromString(pair.lspBId());
        UUID appA = UUID.fromString(pair.appAId());

        // Forced ordering: the edit holds the borrower lock uncommitted while the approval
        // contender starts, provably blocks on it, and only then sees the committed edit.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> TenantScopedExecution.callAsTenant(lspB, () ->
                    transactionTemplate.execute(status -> {
                        Borrower locked = borrowerRepository.findByIdForUpdate(borrowerId).orElseThrow();
                        entityManager.refresh(locked);
                        Lsp lsp = lspRepository.findById(lspB).orElseThrow();
                        borrowerBankUpdatePolicy.applyChangedBankDetails(
                                locked, lsp,
                                "454545454545", "B Bank", EDITED_IFSC, "Shared Borrower",
                                "b.client", "LSP_API_CLIENT", "127.0.0.1");
                        holding.countDown();
                        await(release);
                        return null;
                    })));
            Future<?> contender = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                await(holding);
                attempting.countDown();
                loanApplicationLifecycleService.transitionStatus(
                        appA, "c06p2", LoanApplicationStatus.APPROVED_PENDING_DISBURSAL, "Race approval", null);
                return loanDisbursementCommandService.initiateDisbursement(appA, "c06p2");
            }));
            assertContenderBlockedOnBorrowerLock(contender, attempting);
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
            contender.get(60, TimeUnit.SECONDS);

            assertEquals(EDITED_IFSC, liveIfsc(pair.borrowerId()));
            assertEquals(auditsBefore + 1, auditCount(pair.borrowerId()));
            assertEquals(EDITED_IFSC, liveIntent(pair.appAId()).getBeneficiaryIfsc());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void heldApprovalWinsEditBlockedWithZeroDelta() throws Exception {
        Pair pair = setupSharedBorrowerPair("HA");
        transition(pair.appAId(), "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        long auditsBefore = auditCount(pair.borrowerId());
        int relationshipsBefore = relationshipCount(pair.borrowerId());
        var visibilityBefore = visibleLspIds(pair.borrowerId());
        String nameBefore = fullName(pair.borrowerId());
        UUID borrowerId = UUID.fromString(pair.borrowerId());
        UUID lspB = UUID.fromString(pair.lspBId());
        UUID appA = UUID.fromString(pair.appAId());

        // Forced ordering: approval + initiation hold the borrower lock uncommitted while the
        // edit contender starts, provably blocks, then loses against the committed instruction.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(status -> {
                        loanApplicationLifecycleService.transitionStatus(
                                appA, "c06p2", LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                                "Race approval", null);
                        loanDisbursementCommandService.initiateDisbursement(appA, "c06p2");
                        holding.countDown();
                        await(release);
                        return null;
                    })));
            Future<?> contender = pool.submit(() -> TenantScopedExecution.callAsTenant(lspB, () -> {
                await(holding);
                attempting.countDown();
                return borrowerBankDetailsService.updateBankDetailsForLsp(
                        lspB, borrowerId,
                        new BorrowerBankDetailsCommand(
                                "565656565656", "B Bank", EDITED_IFSC, "Shared Borrower"),
                        "b.client", "127.0.0.1");
            }));
            assertContenderBlockedOnBorrowerLock(contender, attempting);
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
            try {
                contender.get(60, TimeUnit.SECONDS);
                fail("B edit must be rejected while A's instruction is live");
            } catch (java.util.concurrent.ExecutionException rejected) {
                assertTrue(rejected.getCause() instanceof BusinessRuleViolationException);
                assertEquals("BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT",
                        ((BusinessRuleViolationException) rejected.getCause()).getErrorCode());
            }

            assertEquals(FROZEN_IFSC, liveIfsc(pair.borrowerId()));
            assertEquals(auditsBefore, auditCount(pair.borrowerId()));
            assertEquals(relationshipsBefore, relationshipCount(pair.borrowerId()));
            assertEquals(visibilityBefore, visibleLspIds(pair.borrowerId()));
            assertEquals(nameBefore, fullName(pair.borrowerId()));
            assertEquals(FROZEN_IFSC, liveIntent(pair.appAId()).getBeneficiaryIfsc());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void heldApprovalWinsOnboardingLosesWithZeroDelta() throws Exception {
        Pair pair = setupSharedBorrowerPair("HO");
        transition(pair.appAId(), "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        String bankBefore = liveAccount(pair.borrowerId());
        String nameBefore = fullName(pair.borrowerId());
        long auditsBefore = auditCount(pair.borrowerId());
        int relationshipsBefore = relationshipCount(pair.borrowerId());
        var visibilityBefore = visibleLspIds(pair.borrowerId());
        long applicationsBefore = loanApplicationRepository.count();
        UUID appA = UUID.fromString(pair.appAId());

        // Forced ordering: approval holds the borrower lock uncommitted while a second B
        // onboarding starts, provably blocks on the lock, then fails the under-lock
        // eligibility recheck with zero bank/profile/audit/access/relationship delta.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(status -> {
                        loanApplicationLifecycleService.transitionStatus(
                                appA, "c06p2", LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                                "Race approval", null);
                        loanDisbursementCommandService.initiateDisbursement(appA, "c06p2");
                        holding.countDown();
                        await(release);
                        return null;
                    })));
            Future<MvcResult> contender = pool.submit(() -> {
                await(holding);
                attempting.countDown();
                return mockMvc.perform(post("/api/v1/lsp/loan-applications")
                                .header("Authorization", "Bearer " + pair.tokenB())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        externalPayload(pair.lspBId(), pair.productId(),
                                                "EXT-C06P2-HO-B2", pair.pan()))))
                        .andReturn();
            });
            assertContenderBlockedOnBorrowerLock(contender, attempting);
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
            MvcResult onboardingResult = contender.get(60, TimeUnit.SECONDS);
            assertEquals(409, onboardingResult.getResponse().getStatus());
            assertEquals("BORROWER_HAS_ACTIVE_LOAN",
                    objectMapper.readTree(onboardingResult.getResponse().getContentAsString())
                            .get("code").asText());

            assertEquals(applicationsBefore, loanApplicationRepository.count());
            assertEquals(bankBefore, liveAccount(pair.borrowerId()));
            assertEquals(nameBefore, fullName(pair.borrowerId()));
            assertEquals(FROZEN_IFSC, liveIfsc(pair.borrowerId()));
            assertEquals(auditsBefore, auditCount(pair.borrowerId()));
            assertEquals(relationshipsBefore, relationshipCount(pair.borrowerId()));
            assertEquals(visibilityBefore, visibleLspIds(pair.borrowerId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void heldOnboardingWinsBankUpdateAuditedAtomically() throws Exception {
        Pair pair = setupSharedBorrowerPair("HW");
        transition(pair.appAId(), "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(pair.appAId());
        seedBankDetails(pair.borrowerId(), FROZEN_IFSC);
        long auditsBefore = auditCount(pair.borrowerId());
        UUID borrowerId = UUID.fromString(pair.borrowerId());
        UUID lspB = UUID.fromString(pair.lspBId());
        UUID appA = UUID.fromString(pair.appAId());

        // Forced ordering: B's second onboarding (changed bank) holds the borrower lock
        // uncommitted while the approval contender blocks on it; after commit the approval
        // snapshots the onboarded instruction and the bank change is audited exactly once.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> TenantScopedExecution.callAsTenant(lspB, () ->
                    transactionTemplate.execute(status -> {
                        Borrower current = borrowerRepository.findById(borrowerId).orElseThrow();
                        BorrowerProfile base = BorrowerProfile.fromEntity(current);
                        BorrowerProfile incoming = new BorrowerProfile(
                                base.fullName(), base.emailAddress(), base.mobileNumber(),
                                base.dateOfBirth(), base.gender(), base.maritalStatus(),
                                base.fatherName(), base.aadharNumber(), base.panNumber(),
                                base.addressLine1(), base.addressLine2(), base.addressCity(),
                                base.addressState(), base.addressZipcode(), base.spouseName(),
                                base.employmentStatus(), base.organizationName(), base.empId(),
                                base.employmentCity(), base.employmentState(), base.employmentZip(),
                                base.monthlyIncome(), base.annualIncome(),
                                "676767676767", "B2 Bank", EDITED_IFSC, "Shared Borrower",
                                base.referencePersonName(), base.referencePersonNumber());
                        Lsp lsp = lspRepository.findById(lspB).orElseThrow();
                        borrowerOnboardingService.resolveBorrowerForOnboarding(
                                lsp,
                                new LoanApplicationOnboardingCommand(
                                        lspB, UUID.fromString(pair.productId()), null,
                                        "EXT-C06P2-HW-B2", "API",
                                        new BigDecimal("45000.00"), new BigDecimal("18.50"), 12,
                                        incoming),
                                base.monthlyIncome(), base.annualIncome(), "b.client");
                        holding.countDown();
                        await(release);
                        return null;
                    })));
            Future<?> contender = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                await(holding);
                attempting.countDown();
                loanApplicationLifecycleService.transitionStatus(
                        appA, "c06p2", LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                        "Race approval", null);
                return loanDisbursementCommandService.initiateDisbursement(appA, "c06p2");
            }));
            assertContenderBlockedOnBorrowerLock(contender, attempting);
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
            contender.get(60, TimeUnit.SECONDS);

            assertEquals("676767676767", liveAccount(pair.borrowerId()));
            assertEquals(EDITED_IFSC, liveIfsc(pair.borrowerId()));
            assertEquals(auditsBefore + 1, auditCount(pair.borrowerId()));
            assertEquals(EDITED_IFSC, liveIntent(pair.appAId()).getBeneficiaryIfsc());
        } finally {
            pool.shutdownNow();
        }
    }

    // --- fixtures ---

    private record Pair(
            String appAId, String appBId, String borrowerId,
            String lspAId, String lspBId, String tokenB, String pan, String productId) {
    }

    private Pair setupSharedBorrowerPair(String suffix) throws Exception {
        String lspA = createLspViaAdmin("C06P2-" + suffix + "-A");
        String lspB = createLspViaAdmin("C06P2-" + suffix + "-B");
        String product = createProductViaAdmin();
        mapProductToLsps(product, List.of(lspA, lspB));
        String tokenB = issueToken(createApiClient(lspB));

        String pan = TestPanSequence.uniquePan();
        JsonNode appA = createApplicationViaLsp(issueToken(createApiClient(lspA)), lspA, product,
                "EXT-C06P2-" + suffix + "-A", pan);
        JsonNode appB = createApplicationViaLsp(tokenB, lspB, product,
                "EXT-C06P2-" + suffix + "-B", pan);
        String borrowerA = appA.get("borrowerId").asText();
        assertEquals(borrowerA, appB.get("borrowerId").asText());
        return new Pair(
                appA.get("id").asText(), appB.get("id").asText(), borrowerA, lspA, lspB, tokenB, pan, product);
    }

    private void approve(String applicationId) throws Exception {
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for phase-2 test");
    }

    private void initiate(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(UUID.fromString(applicationId));
    }

    private void seedBankDetails(String borrowerId, String ifsc) throws Exception {
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "Test Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private DisbursementIntent liveIntent(String applicationId) {
        UUID accountId = loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId))
                .orElseThrow().getId();
        return disbursementIntentRepository.findLiveByLoanAccountId(accountId).orElseThrow();
    }

    private LoanAccountStatus accountStatus(String applicationId) {
        return loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId))
                .orElseThrow().getStatus();
    }

    private String liveIfsc(String borrowerId) {
        return borrowerRepository.findById(UUID.fromString(borrowerId)).orElseThrow().getIfscCode();
    }

    private String liveAccount(String borrowerId) {
        return borrowerRepository.findById(UUID.fromString(borrowerId)).orElseThrow().getBankAccountNumber();
    }

    private String fullName(String borrowerId) {
        return borrowerRepository.findById(UUID.fromString(borrowerId)).orElseThrow().getFullName();
    }

    private java.util.Set<UUID> visibleLspIds(String borrowerId) {
        return new java.util.LinkedHashSet<>(
                borrowerRepository.findById(UUID.fromString(borrowerId)).orElseThrow().getVisibleLspIds());
    }

    private int relationshipCount(String borrowerId) {
        return borrowerLspRelationshipRepository
                .findByBorrower_IdOrderByFirstSourcedAtAsc(UUID.fromString(borrowerId))
                .size();
    }

    private long auditCount(String borrowerId) {
        UUID id = UUID.fromString(borrowerId);
        return bankDetailsUpdateAuditRepository.findAll().stream()
                .filter(audit -> audit.getBorrower().getId().equals(id))
                .count();
    }

    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Race gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Race gate interrupted", interrupted);
        }
    }

    /**
     * Proves the contender is genuinely waiting on the winner's borrower row lock (a granted
     * {@code FOR UPDATE} vs. an ungranted waiter in {@code pg_locks}), not merely slow, before
     * the winner is released.
     */
    private void assertContenderBlockedOnBorrowerLock(Future<?> contender, CountDownLatch attempting)
            throws Exception {
        assertTrue(attempting.await(30, TimeUnit.SECONDS), "Contender never reached its lock attempt");
        awaitBorrowerLockContention(contender);
        assertFalse(contender.isDone(), "Contender finished without waiting for the borrower lock");
    }

    private void awaitBorrowerLockContention(Future<?> contender) throws Exception {
        // The winner holds the borrower row locked with uncommitted writes, so the contender
        // waits either on the row itself (tuple) or on the winner's transaction id — both are
        // ungranted locks in pg_locks, and no other activity runs during the hold window.
        long deadline = System.currentTimeMillis() + 15000;
        while (true) {
            if (contender.isDone()) {
                // Do not mask a contender failure as a missing lock wait: surface its cause.
                contender.get(1, TimeUnit.SECONDS);
                fail("Contender finished without waiting for the borrower lock");
            }
            Number waiting = TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(status -> (Number) entityManager.createNativeQuery(
                                    "SELECT COUNT(*) FROM pg_locks WHERE NOT granted")
                            .getSingleResult()));
            if (waiting.longValue() > 0) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("Expected a contender blocked behind the winner's open transaction (pg_locks)");
            }
            Thread.sleep(150);
        }
    }

    private void adminBankEdit(String borrowerId, String accountNumber) throws Exception {
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", accountNumber,
                                "bankName", "Velocity Bank",
                                "ifscCode", FROZEN_IFSC,
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private void tenantBankEdit(Pair pair, String accountNumber) throws Exception {
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", pair.borrowerId())
                        .header("Authorization", "Bearer " + pair.tokenB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", accountNumber,
                                "bankName", "Velocity Bank",
                                "ifscCode", FROZEN_IFSC,
                                "accountHolderName", "Shared Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private boolean hasVelocityAlert(String borrowerId) {
        UUID id = UUID.fromString(borrowerId);
        return opsAlertRepository.findAll().stream()
                .anyMatch(alert -> alert.getType() == OpsAlertType.BORROWER_BANK_DETAILS_VELOCITY
                        && id.equals(alert.getSubjectId()));
    }

    private JsonNode createApplicationViaLsp(
            String accessToken, String lspId, String productId, String externalLoanId, String pan
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                externalPayload(lspId, productId, externalLoanId, pan))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, Object> externalPayload(
            String lspId, String productId, String externalLoanId, String pan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("lspLoanId", externalLoanId);
        payload.put("fullName", "Shared Borrower");
        payload.put("emailAddress", externalLoanId.toLowerCase() + "@example.com");
        payload.put("mobileNumber", mobileForPan(pan));
        payload.put("dob", "1990-01-01");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Ramesh Sharma");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", pan);
        payload.put("loanAmount", new BigDecimal("45000.00"));
        payload.put("interestRate", new BigDecimal("18.50"));
        payload.put("loanTenure", 12);
        payload.put("addressLine1", "Palm Residency");
        payload.put("addressLine2", "Andheri East");
        payload.put("addressCity", "Mumbai");
        payload.put("addressState", "Maharashtra");
        payload.put("addressZipcode", "400001");
        payload.put("employmentStatus", "SALARIED");
        payload.put("organizationName", "Apex Corp");
        payload.put("empId", "EMP-001");
        payload.put("employmentCity", "Mumbai");
        payload.put("employmentState", "Maharashtra");
        payload.put("employmentZip", "400001");
        payload.put("monthlyIncome", new BigDecimal("78000.00"));
        payload.put("annualIncome", new BigDecimal("936000.00"));
        payload.put("bankAccountNumber", "123456789012");
        payload.put("bankName", "Demo Bank");
        payload.put("ifscCode", "HDFC0001234");
        payload.put("accountHolderName", "Shared Borrower");
        payload.put("referencePersonName", "Neha Verma");
        payload.put("referencePersonNumber", "9888877777");
        return payload;
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
                            "Uploaded for phase-2 test",
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

    private JsonNode createApiClient(String lspId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Test phase-2 client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String issueToken(JsonNode apiClient) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", apiClient.get("clientId").asText(),
                                "clientSecret", apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String createLspViaAdmin(String codeSuffix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + codeSuffix,
                                "name", "Test phase-2 LSP " + codeSuffix,
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
                                "name", "Test phase-2 product " + code,
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mapProductToLsps(productId, List.of(lspId));
    }

    private void mapProductToLsps(String productId, List<String> lspIds) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", lspIds))))
                .andExpect(status().isOk());
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
}
