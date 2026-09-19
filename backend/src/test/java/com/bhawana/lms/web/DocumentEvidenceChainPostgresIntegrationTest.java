package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.reset;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerLspRelationship;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerLspRelationshipRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.service.DocumentStorageProperties;
import com.bhawana.lms.service.LoanApplicationDocumentChecklistService;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanDocumentOrphanReconciler;
import com.bhawana.lms.service.LoanDocumentService;
import com.bhawana.lms.service.LoanDocumentService.BatchDocumentUpload;
import com.bhawana.lms.service.LoanDocumentStorageService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.LoanProductVersionTestSupport;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approval & document evidence chain (consolidated audit H13, H14, M04) against real
 * PostgreSQL transactions, tenant-scoped so the V131 row-level-security policies are exercised.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentEvidenceChainPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    private static final String ACTOR = "lsp.api";

    private static final List<LoanApplicationDocumentType> REQUIRED = List.of(
            LoanApplicationDocumentType.PAN_CARD,
            LoanApplicationDocumentType.AADHAAR_FILE,
            LoanApplicationDocumentType.ADDRESS_PROOF,
            LoanApplicationDocumentType.INCOME_PROOF,
            LoanApplicationDocumentType.BANK_STATEMENT,
            LoanApplicationDocumentType.SELFIE_PHOTOGRAPH,
            LoanApplicationDocumentType.KFS,
            LoanApplicationDocumentType.LOAN_AGREEMENT
    );

    @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LspRepository lspRepository;
    @Autowired private LoanProductRepository loanProductRepository;
    @Autowired private LoanProductVersionRepository loanProductVersionRepository;
    @Autowired private LoanProductLspMappingRepository mappingRepository;
    @Autowired private BorrowerRepository borrowerRepository;
    @Autowired private BorrowerLspRelationshipRepository borrowerLspRelationshipRepository;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanDocumentService loanDocumentService;
    @Autowired private LoanDocumentOrphanReconciler orphanReconciler;
    @Autowired private DocumentStorageProperties documentStorageProperties;

    @MockitoSpyBean private LoanApplicationLifecycleService lifecycleService;
    @MockitoSpyBean private LoanApplicationDocumentChecklistService documentChecklistService;
    @MockitoSpyBean private LoanDocumentStorageService loanDocumentStorageService;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void resetSpies() {
        reset(lifecycleService, documentChecklistService, loanDocumentStorageService);
    }

    // ---------------------------------------------------------------- H13

    @Test
    void finalUploadWaitsForConcurrentUploadAndApprovesExactlyOnce() throws Exception {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        uploadAllExcept(fixture, LoanApplicationDocumentType.KFS, LoanApplicationDocumentType.LOAN_AGREEMENT);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // A records KFS and keeps its transaction open; without serialization B would read the
            // checklist without A's KFS, A without B's agreement, and neither would see completion.
            Future<?> first = pool.submit(() -> TenantScopedExecution.callAsTenant(fixture.lspId(), () ->
                    transactionTemplate.execute(status -> {
                        upload(fixture, LoanApplicationDocumentType.KFS, "kfs");
                        holding.countDown();
                        await(release);
                        return null;
                    })));
            assertTrue(holding.await(30, TimeUnit.SECONDS), "first upload never reached its hold point");
            Future<?> second = pool.submit(() ->
                    upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement"));

            awaitLockContention(second);
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }

        assertApprovedExactlyOnce(fixture);
    }

    @Test
    void lastTwoRequiredDocumentsUploadedConcurrentlyApproveExactlyOnce() throws Exception {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        uploadAllExcept(fixture, LoanApplicationDocumentType.KFS, LoanApplicationDocumentType.LOAN_AGREEMENT);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<LoanApplicationDocumentChecklist>> uploads = List.of(
                    pool.submit(whenReleased(ready, start,
                            () -> upload(fixture, LoanApplicationDocumentType.KFS, "kfs"))),
                    pool.submit(whenReleased(ready, start,
                            () -> upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement")))
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<LoanApplicationDocumentChecklist> upload : uploads) {
                upload.get(30, TimeUnit.SECONDS);
            }
        }

        assertApprovedExactlyOnce(fixture);
    }

    @Test
    void crashBetweenMetadataAndApprovalCommitsNeitherAndRetryApprovesOnce() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        uploadAllExcept(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT);

        // Process death after the final document's metadata is written but before the approval
        // decision: both are in one transaction, so the failure rolls the metadata back too.
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated process death before approval");
            }
            return invocation.callRealMethod();
        }).when(lifecycleService).autoApproveIfEligibleForLsp(any(), anyString());

        assertThatThrownBy(() -> upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement"))
                .hasMessageContaining("simulated process death");

        assertThat(status(fixture)).isEqualTo(LoanApplicationStatus.INITIALIZED);
        assertThat(checklistStatus(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT)).isEqualTo("PENDING");
        assertThat(versionCount(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT)).isZero();
        assertThat(documentsUploadedEvents(fixture)).isZero();
        // The object write happened, and it is owned: PENDING, not lost.
        assertThat(objectStates(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT)).containsExactly("PENDING");

        upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement");

        assertApprovedExactlyOnce(fixture);
        // Same bytes, same key: the retry converged on the first attempt's object.
        assertThat(objectStates(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT)).containsExactly("LINKED");
    }

    @Test
    void duplicateFinalUploadsConvergeOnOneVersionAndOneApproval() throws Exception {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        uploadAllExcept(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<LoanApplicationDocumentChecklist>> uploads = List.of(
                    pool.submit(whenReleased(ready, start,
                            () -> upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement"))),
                    pool.submit(whenReleased(ready, start,
                            () -> upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement")))
            );
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertThat(uploads.get(0).get(30, TimeUnit.SECONDS).getId())
                    .isEqualTo(uploads.get(1).get(30, TimeUnit.SECONDS).getId());
        }
        // A later retry of the same upload (no idempotency key) is also a no-op, not a 409.
        upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement");

        assertApprovedExactlyOnce(fixture);
        assertThat(versionCount(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT)).isEqualTo(1);
    }

    @Test
    void noneligibleApplicationIsRejectedOnceAndLaterUploadsDoNotReapprove() {
        Fixture fixture = createFixture(LoanApplicationStatus.AWAITING_APPROVAL);
        jdbcTemplate.update("UPDATE loan_product_lsp_mapping SET enabled = false WHERE lsp_id = ?", fixture.lspId());
        uploadAllExcept(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT);

        upload(fixture, LoanApplicationDocumentType.LOAN_AGREEMENT, "agreement");

        assertThat(status(fixture)).isEqualTo(LoanApplicationStatus.REJECTED);
        assertThat(documentsUploadedEvents(fixture)).isEqualTo(1);
        assertThat(transitionsTo(fixture, LoanApplicationStatus.REJECTED)).isEqualTo(1);
        assertThat(loanAccounts(fixture)).isZero();
        assertThat(evidenceRows(fixture)).isZero();

        // #135: a rejected application still accepts documents (ops may reopen it), but the
        // gate is repeatable-safe: no second evaluation, event or transition.
        upload(fixture, LoanApplicationDocumentType.PAN_CARD, "late pan");
        assertThat(status(fixture)).isEqualTo(LoanApplicationStatus.REJECTED);
        assertThat(documentsUploadedEvents(fixture)).isEqualTo(1);
        assertThat(transitionsTo(fixture, LoanApplicationStatus.REJECTED)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- H14

    @Test
    void approvalCapturesTheExactVersionsAndChecksums() {
        Fixture fixture = approvedFixture();

        List<Map<String, Object>> evidence = jdbcTemplate.queryForList("""
                SELECT e.document_version_id, e.file_checksum AS evidence_checksum,
                       v.file_checksum AS version_checksum, c.current_version_id
                FROM loan_application_approval_evidence e
                JOIN loan_application_document_checklist c
                  ON c.loan_application_id = e.loan_application_id AND c.document_type = e.document_type
                LEFT JOIN loan_application_document_version v ON v.id = e.document_version_id
                WHERE e.loan_application_id = ?
                """, fixture.applicationId());
        assertThat(evidence).hasSize(REQUIRED.size());
        assertThat(evidence).allSatisfy(row -> {
            assertThat(row.get("document_version_id")).isNotNull().isEqualTo(row.get("current_version_id"));
            assertThat(row.get("evidence_checksum")).isNotNull().isEqualTo(row.get("version_checksum"));
        });
    }

    @Test
    void ordinaryUploadAfterApprovalCannotReplaceApprovedEvidence() {
        Fixture fixture = approvedFixture();
        UUID approvedVersion = currentVersionId(fixture, LoanApplicationDocumentType.PAN_CARD);
        long objectsBefore = objectCount(fixture);

        assertThatThrownBy(() -> upload(fixture, LoanApplicationDocumentType.PAN_CARD, "different pan"))
                .isInstanceOf(ApiConflictException.class)
                .extracting("errorCode").isEqualTo("DOCUMENT_EVIDENCE_LOCKED");
        assertThatThrownBy(() -> TenantScopedExecution.callAsTenant(fixture.lspId(), () ->
                loanDocumentService.submitDocumentMetadataForLsp(
                        fixture.lspId(), fixture.applicationId(), LoanApplicationDocumentType.PAN_CARD, ACTOR,
                        "metadata swap", "pan.pdf", "https://example.test/pan.pdf", null, "application/pdf")))
                .isInstanceOf(ApiConflictException.class)
                .extracting("errorCode").isEqualTo("DOCUMENT_EVIDENCE_LOCKED");

        assertThat(currentVersionId(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo(approvedVersion);
        assertThat(versionCount(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo(1);
        // Rejected before any object was created (H14 ordering; M04 prevalidation).
        assertThat(objectCount(fixture)).isEqualTo(objectsBefore);
    }

    @Test
    void referenceApprovedDocumentMayReceiveItsLmsCopyWhileApprovalKeepsTheReference() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        TenantScopedExecution.callAsTenant(fixture.lspId(), () -> loanDocumentService.submitDocumentMetadataForLsp(
                fixture.lspId(), fixture.applicationId(), LoanApplicationDocumentType.PAN_CARD, ACTOR,
                "external pan", "pan.pdf", "https://example.test/pan.pdf", null, "application/pdf"));
        uploadAllExcept(fixture, LoanApplicationDocumentType.PAN_CARD);
        assertThat(status(fixture)).isEqualTo(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
        UUID approvedReference = currentVersionId(fixture, LoanApplicationDocumentType.PAN_CARD);

        upload(fixture, LoanApplicationDocumentType.PAN_CARD, "pan custody copy");

        assertThat(evidenceVersionId(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo(approvedReference);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT application_status FROM loan_application_document_version
                WHERE loan_application_id = ? AND document_type = 'PAN_CARD' AND version_number = 2
                """, String.class, fixture.applicationId())).isEqualTo("APPROVED_PENDING_DISBURSAL");
        // Once the LMS holds the copy, it is locked like any other approved-stage evidence.
        assertThatThrownBy(() -> upload(fixture, LoanApplicationDocumentType.PAN_CARD, "another pan"))
                .extracting("errorCode").isEqualTo("DOCUMENT_EVIDENCE_LOCKED");
    }

    @Test
    void authorizedCorrectionKeepsBothVersionsAttributionAndApprovalReference() throws Exception {
        Fixture fixture = approvedFixture();
        UUID approvedVersion = currentVersionId(fixture, LoanApplicationDocumentType.PAN_CARD);
        String approvedKey = storageKey(approvedVersion);

        TenantScopedExecution.callAsTenant(fixture.lspId(), () -> loanDocumentService.submitStoredDocumentForLsp(
                fixture.lspId(), fixture.applicationId(), LoanApplicationDocumentType.PAN_CARD, "ops.corrector",
                "corrected pan", null, "Blurred scan replaced with legible copy",
                pdf(LoanApplicationDocumentType.PAN_CARD, "corrected pan")));

        List<Map<String, Object>> versions = jdbcTemplate.queryForList("""
                SELECT id, version_number, kind, correction_reason, corrects_evidence_id,
                       recorded_by_username, storage_key
                FROM loan_application_document_version
                WHERE loan_application_id = ? AND document_type = 'PAN_CARD'
                ORDER BY version_number
                """, fixture.applicationId());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("id")).isEqualTo(approvedVersion);
        assertThat(versions.get(0).get("kind")).isEqualTo("SUBMISSION");
        assertThat(versions.get(1).get("kind")).isEqualTo("CORRECTION");
        assertThat(versions.get(1).get("correction_reason")).isEqualTo("Blurred scan replaced with legible copy");
        assertThat(versions.get(1).get("recorded_by_username")).isEqualTo("ops.corrector");
        assertThat(versions.get(1).get("corrects_evidence_id")).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT id FROM loan_application_approval_evidence WHERE loan_application_id = ? AND document_type = 'PAN_CARD'",
                UUID.class, fixture.applicationId()));
        // The approval still points at what was approved, and both objects are retained.
        assertThat(evidenceVersionId(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo(approvedVersion);
        String correctedKey = (String) versions.get(1).get("storage_key");
        assertThat(correctedKey).isNotEqualTo(approvedKey);
        assertThat(Files.exists(storagePath(approvedKey))).isTrue();
        assertThat(Files.exists(storagePath(correctedKey))).isTrue();
        assertThat(objectStates(fixture, LoanApplicationDocumentType.PAN_CARD)).containsOnly("LINKED");
    }

    @Test
    void correctionIsRefusedBeforeApproval() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        assertThatThrownBy(() -> TenantScopedExecution.callAsTenant(fixture.lspId(), () ->
                loanDocumentService.submitStoredDocumentForLsp(
                        fixture.lspId(), fixture.applicationId(), LoanApplicationDocumentType.PAN_CARD, ACTOR,
                        null, null, "not yet approved", pdf(LoanApplicationDocumentType.PAN_CARD, "pan"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("errorCode").isEqualTo("DOCUMENT_CORRECTION_NOT_APPLICABLE");
        assertThat(objectCount(fixture)).isZero();
    }

    @Test
    void replacementCommittedFirstIsTheEvidenceTheRacingApprovalCaptures() throws Exception {
        Fixture fixture = awaitingManualApprovalFixture();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> replacement = pool.submit(() -> TenantScopedExecution.callAsTenant(fixture.lspId(), () ->
                    transactionTemplate.execute(status -> {
                        upload(fixture, LoanApplicationDocumentType.PAN_CARD, "replacement pan");
                        holding.countDown();
                        await(release);
                        return null;
                    })));
            assertTrue(holding.await(30, TimeUnit.SECONDS));
            Future<?> approval = pool.submit(() -> manuallyApprove(fixture));
            awaitLockContention(approval);
            release.countDown();
            replacement.get(30, TimeUnit.SECONDS);
            approval.get(30, TimeUnit.SECONDS);
        }

        assertThat(versionCount(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo(2);
        assertEvidenceMatchesCurrentChecklist(fixture);
    }

    @Test
    void approvalCommittedFirstLocksTheRacingReplacementOut() throws Exception {
        Fixture fixture = awaitingManualApprovalFixture();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> approval = pool.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(status -> {
                        manuallyApprove(fixture);
                        holding.countDown();
                        await(release);
                        return null;
                    })));
            assertTrue(holding.await(30, TimeUnit.SECONDS));
            Future<?> replacement = pool.submit(() ->
                    upload(fixture, LoanApplicationDocumentType.PAN_CARD, "replacement pan"));
            awaitLockContention(replacement);
            release.countDown();
            approval.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> replacement.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiConflictException.class)
                    .hasMessageContaining("approved evidence");
        }

        assertThat(versionCount(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo(1);
        assertEvidenceMatchesCurrentChecklist(fixture);
    }

    // ---------------------------------------------------------------- M04

    @Test
    void lastItemValidationFailureWritesNoObjectsForTheBatch() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        List<BatchDocumentUpload> batch = List.of(
                batchItem(LoanApplicationDocumentType.PAN_CARD, "pan"),
                batchItem(LoanApplicationDocumentType.AADHAAR_FILE, "aadhaar"),
                // KFS is PDF-only: the last item fails content validation.
                new BatchDocumentUpload(LoanApplicationDocumentType.KFS, null, null,
                        new MockMultipartFile("file", "kfs.png", "image/png", new byte[] {1, 2, 3}))
        );

        assertThatThrownBy(() -> uploadBatch(fixture, batch)).isInstanceOf(RuntimeException.class);

        assertThat(objectCount(fixture)).isZero();
        assertThat(storedFiles(fixture)).isEmpty();
        assertThat(totalVersions(fixture)).isZero();
    }

    @Test
    void lastItemUploadPolicyFailureWritesNoObjectsForTheBatch() {
        Fixture fixture = approvedFixture();
        long objectsBefore = objectCount(fixture);
        List<BatchDocumentUpload> batch = List.of(
                // Both types are approved evidence; the batch is refused before any write.
                batchItem(LoanApplicationDocumentType.INCOME_PROOF, "new income"),
                batchItem(LoanApplicationDocumentType.PAN_CARD, "new pan")
        );

        assertThatThrownBy(() -> uploadBatch(fixture, batch))
                .extracting("errorCode").isEqualTo("DOCUMENT_EVIDENCE_LOCKED");
        assertThat(objectCount(fixture)).isEqualTo(objectsBefore);
    }

    @Test
    void metadataFailureAfterStorageRetriesWithoutDuplicatesAndOnlyTrueOrphansAreCleaned() throws Exception {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        List<BatchDocumentUpload> batch = List.of(
                batchItem(LoanApplicationDocumentType.PAN_CARD, "pan"),
                batchItem(LoanApplicationDocumentType.AADHAAR_FILE, "aadhaar")
        );
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated metadata failure");
            }
            return invocation.callRealMethod();
        }).when(documentChecklistService).recordSubmissions(any(), anyString(), anyList(), any());

        assertThatThrownBy(() -> uploadBatch(fixture, batch)).hasMessageContaining("simulated metadata failure");
        assertThat(objectCount(fixture)).isEqualTo(2);
        assertThat(totalVersions(fixture)).isZero();

        uploadBatch(fixture, batch);

        // Converged on the same two objects; one version and one checklist entry per type.
        assertThat(jdbcTemplate.queryForList(
                "SELECT state FROM loan_document_object WHERE loan_application_id = ?",
                String.class, fixture.applicationId())).containsExactly("LINKED", "LINKED");
        assertThat(versionCount(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo(1);
        assertThat(versionCount(fixture, LoanApplicationDocumentType.AADHAAR_FILE)).isEqualTo(1);

        // An abandoned attempt (different bytes, metadata never committed) leaves a true orphan.
        doAnswer(invocation -> {
            throw new IllegalStateException("abandoned attempt");
        }).when(documentChecklistService).recordSubmissions(any(), anyString(), anyList(), any());
        assertThatThrownBy(() -> uploadBatch(fixture,
                List.of(batchItem(LoanApplicationDocumentType.ADDRESS_PROOF, "abandoned"))))
                .hasMessageContaining("abandoned attempt");
        doCallRealMethod().when(documentChecklistService).recordSubmissions(any(), anyString(), anyList(), any());
        String orphanKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM loan_document_object WHERE document_type = 'ADDRESS_PROOF'", String.class);
        // A historical object the reconciler has no record of must never be touched.
        Path historical = storagePath("loan/" + fixture.applicationId() + "/pan_card/1700000000000-legacy.pdf");
        Files.createDirectories(historical.getParent());
        Files.writeString(historical, "%PDF-1.4 legacy");

        Instant withinGrace = Instant.now().plus(Duration.ofHours(1));
        assertThat(reconcile(withinGrace, false).candidates()).isZero();

        Instant afterGrace = Instant.now().plus(documentStorageProperties.getOrphanReconciler().getGracePeriod())
                .plus(Duration.ofMinutes(5));
        LoanDocumentOrphanReconciler.Result dryRun = reconcile(afterGrace, true);
        assertThat(dryRun.candidates()).isEqualTo(1);
        assertThat(dryRun.deleted()).isZero();
        assertThat(Files.exists(storagePath(orphanKey))).isTrue();

        LoanDocumentOrphanReconciler.Result result = reconcile(afterGrace, false);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(Files.exists(storagePath(orphanKey))).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM loan_document_object WHERE storage_key = ?", String.class, orphanKey))
                .isEqualTo("DELETED");
        assertThat(storedFiles(fixture)).hasSize(3); // two linked objects + the untouched historical one
        assertThat(Files.exists(historical)).isTrue();

        // A later upload of the deleted bytes re-arms the record and links it normally.
        uploadBatch(fixture, List.of(batchItem(LoanApplicationDocumentType.ADDRESS_PROOF, "abandoned")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM loan_document_object WHERE storage_key = ?", String.class, orphanKey))
                .isEqualTo("LINKED");
        assertThat(Files.exists(storagePath(orphanKey))).isTrue();
    }

    @Test
    void reconcilerNeverDeletesAReferencedObjectEvenIfItsRecordLooksPending() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        upload(fixture, LoanApplicationDocumentType.PAN_CARD, "pan");
        String key = storageKey(currentVersionId(fixture, LoanApplicationDocumentType.PAN_CARD));
        jdbcTemplate.update("UPDATE loan_document_object SET state = 'PENDING' WHERE storage_key = ?", key);

        LoanDocumentOrphanReconciler.Result result = reconcile(Instant.now().plus(Duration.ofDays(30)), false);

        assertThat(result.deleted()).isZero();
        assertThat(result.relinked()).isEqualTo(1);
        assertThat(Files.exists(storagePath(key))).isTrue();
    }

    @Test
    void partialObjectWriteFailureCommitsNoMetadataAndRetrySucceeds() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        List<BatchDocumentUpload> batch = List.of(
                batchItem(LoanApplicationDocumentType.PAN_CARD, "pan"),
                batchItem(LoanApplicationDocumentType.AADHAAR_FILE, "aadhaar"),
                batchItem(LoanApplicationDocumentType.ADDRESS_PROOF, "address")
        );
        AtomicInteger stores = new AtomicInteger();
        doAnswer(invocation -> {
            if (stores.incrementAndGet() == 2) {
                throw new IllegalStateException("storage unavailable");
            }
            return invocation.callRealMethod();
        }).when(loanDocumentStorageService).store(any(LoanDocumentStorageService.PreparedDocument.class));

        assertThatThrownBy(() -> uploadBatch(fixture, batch)).hasMessageContaining("storage unavailable");
        assertThat(totalVersions(fixture)).isZero();
        assertThat(checklistStatus(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo("PENDING");
        // Both attempted objects are owned (PENDING) — the written one and the failed one.
        assertThat(objectCount(fixture)).isEqualTo(2);

        uploadBatch(fixture, batch);

        assertThat(totalVersions(fixture)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList(
                "SELECT state FROM loan_document_object WHERE loan_application_id = ?",
                String.class, fixture.applicationId())).containsOnly("LINKED").hasSize(3);
    }

    @Test
    void ownershipRecordSurvivesARolledBackOuterTransaction() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);

        // The idempotent API path runs the whole upload inside an outer transaction.
        TenantScopedExecution.callAsTenant(fixture.lspId(), () -> transactionTemplate.execute(status -> {
            upload(fixture, LoanApplicationDocumentType.PAN_CARD, "pan");
            status.setRollbackOnly();
            return null;
        }));

        assertThat(checklistStatus(fixture, LoanApplicationDocumentType.PAN_CARD)).isEqualTo("PENDING");
        assertThat(objectStates(fixture, LoanApplicationDocumentType.PAN_CARD)).containsExactly("PENDING");
        assertThat(storedFiles(fixture)).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private void assertApprovedExactlyOnce(Fixture fixture) {
        assertThat(status(fixture)).isEqualTo(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
        assertThat(documentsUploadedEvents(fixture)).isEqualTo(1);
        assertThat(transitionsTo(fixture, LoanApplicationStatus.APPROVED_PENDING_DISBURSAL)).isEqualTo(1);
        assertThat(loanAccounts(fixture)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT approval_id) FROM loan_application_approval_evidence WHERE loan_application_id = ?",
                Long.class, fixture.applicationId())).isEqualTo(1);
        assertEvidenceMatchesCurrentChecklist(fixture);
    }

    private void assertEvidenceMatchesCurrentChecklist(Fixture fixture) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.document_version_id, c.current_version_id, e.file_checksum, c.file_checksum AS current_checksum
                FROM loan_application_approval_evidence e
                JOIN loan_application_document_checklist c
                  ON c.loan_application_id = e.loan_application_id AND c.document_type = e.document_type
                WHERE e.loan_application_id = ?
                """, fixture.applicationId());
        assertThat(rows).hasSize(REQUIRED.size());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("document_version_id")).isEqualTo(row.get("current_version_id"));
            assertThat(row.get("file_checksum")).isEqualTo(row.get("current_checksum"));
        });
    }

    private Fixture approvedFixture() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        uploadAllExcept(fixture);
        assertThat(status(fixture)).isEqualTo(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL);
        return fixture;
    }

    /** All documents in, rules passing, application waiting for a manual ops approval. */
    private Fixture awaitingManualApprovalFixture() {
        Fixture fixture = createFixture(LoanApplicationStatus.INITIALIZED);
        // Keep the gate from auto-approving while the documents go in.
        jdbcTemplate.update("UPDATE loan_product_lsp_mapping SET enabled = false WHERE lsp_id = ?", fixture.lspId());
        uploadAllExcept(fixture);
        jdbcTemplate.update("UPDATE loan_product_lsp_mapping SET enabled = true WHERE lsp_id = ?", fixture.lspId());
        TenantScopedExecution.callAsAdmin(() -> lifecycleService.transitionStatus(
                fixture.applicationId(), "ops.reviewer", LoanApplicationStatus.AWAITING_APPROVAL, "review", null));
        assertThat(status(fixture)).isEqualTo(LoanApplicationStatus.AWAITING_APPROVAL);
        return fixture;
    }

    private LoanApplication manuallyApprove(Fixture fixture) {
        return TenantScopedExecution.callAsAdmin(() -> lifecycleService.transitionStatus(
                fixture.applicationId(), "ops.approver", LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                "approved on review", (LoanApplicationStatusReasonCode) null));
    }

    private void uploadAllExcept(Fixture fixture, LoanApplicationDocumentType... excluded) {
        List<LoanApplicationDocumentType> skip = List.of(excluded);
        for (LoanApplicationDocumentType type : REQUIRED) {
            if (!skip.contains(type)) {
                upload(fixture, type, type.name().toLowerCase());
            }
        }
    }

    private LoanApplicationDocumentChecklist upload(Fixture fixture, LoanApplicationDocumentType type, String content) {
        return TenantScopedExecution.callAsTenant(fixture.lspId(), () -> loanDocumentService.submitStoredDocumentForLsp(
                fixture.lspId(), fixture.applicationId(), type, ACTOR, "note " + type.name(), null,
                pdf(type, content)));
    }

    private List<LoanApplicationDocumentChecklist> uploadBatch(Fixture fixture, List<BatchDocumentUpload> batch) {
        return TenantScopedExecution.callAsTenant(fixture.lspId(), () ->
                loanDocumentService.submitStoredDocumentsForLsp(fixture.lspId(), fixture.applicationId(), ACTOR, batch));
    }

    private static BatchDocumentUpload batchItem(LoanApplicationDocumentType type, String content) {
        return new BatchDocumentUpload(type, "note " + type.name(), null, pdf(type, content));
    }

    private static MockMultipartFile pdf(LoanApplicationDocumentType type, String content) {
        return new MockMultipartFile(
                "file",
                type.name().toLowerCase() + ".pdf",
                "application/pdf",
                ("%PDF-1.4 " + content).getBytes(StandardCharsets.UTF_8)
        );
    }

    private LoanDocumentOrphanReconciler.Result reconcile(Instant now, boolean dryRun) {
        return TenantScopedExecution.callAsAdmin(() -> orphanReconciler.reconcile(now, dryRun));
    }

    private LoanApplicationStatus status(Fixture fixture) {
        return LoanApplicationStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM loan_application WHERE id = ?", String.class, fixture.applicationId()));
    }

    private String checklistStatus(Fixture fixture, LoanApplicationDocumentType type) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM loan_application_document_checklist WHERE loan_application_id = ? AND document_type = ?",
                String.class, fixture.applicationId(), type.name());
    }

    private UUID currentVersionId(Fixture fixture, LoanApplicationDocumentType type) {
        return jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM loan_application_document_checklist WHERE loan_application_id = ? AND document_type = ?",
                UUID.class, fixture.applicationId(), type.name());
    }

    private UUID evidenceVersionId(Fixture fixture, LoanApplicationDocumentType type) {
        return jdbcTemplate.queryForObject(
                "SELECT document_version_id FROM loan_application_approval_evidence WHERE loan_application_id = ? AND document_type = ?",
                UUID.class, fixture.applicationId(), type.name());
    }

    private String storageKey(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_key FROM loan_application_document_version WHERE id = ?", String.class, versionId);
    }

    private long versionCount(Fixture fixture, LoanApplicationDocumentType type) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loan_application_document_version WHERE loan_application_id = ? AND document_type = ?",
                Long.class, fixture.applicationId(), type.name());
    }

    private long totalVersions(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loan_application_document_version WHERE loan_application_id = ?",
                Long.class, fixture.applicationId());
    }

    private long objectCount(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loan_document_object WHERE loan_application_id = ?",
                Long.class, fixture.applicationId());
    }

    private List<String> objectStates(Fixture fixture, LoanApplicationDocumentType type) {
        return jdbcTemplate.queryForList(
                "SELECT state FROM loan_document_object WHERE loan_application_id = ? AND document_type = ?",
                String.class, fixture.applicationId(), type.name());
    }

    private long documentsUploadedEvents(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loan_event WHERE loan_application_id = ? AND event_type = 'DOCUMENTS_UPLOADED'",
                Long.class, fixture.applicationId());
    }

    private long transitionsTo(Fixture fixture, LoanApplicationStatus target) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loan_application_status_transition WHERE loan_application_id = ? AND to_status = ?",
                Long.class, fixture.applicationId(), target.name());
    }

    private long loanAccounts(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loan_account WHERE loan_application_id = ?", Long.class, fixture.applicationId());
    }

    private long evidenceRows(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loan_application_approval_evidence WHERE loan_application_id = ?",
                Long.class, fixture.applicationId());
    }

    private Path storagePath(String storageKey) {
        return documentStorageProperties.getRootPath().resolve(storageKey);
    }

    private List<Path> storedFiles(Fixture fixture) {
        Path directory = storagePath("loan/" + fixture.applicationId());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int lockWaiters() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()",
                Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Positive proof that the contender reached PostgreSQL lock wait behind the holder's open
     * transaction (bounded poll, same technique as ScheduleReplacementFreezeIntegrationTest).
     */
    private void awaitLockContention(Future<?> contender) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (contender.isDone()) {
                contender.get(1, TimeUnit.SECONDS);
                throw new AssertionError("contender finished without waiting for the document-write lock");
            }
            if (lockWaiters() >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("expected the contender to wait on the holder's lock");
    }

    private static <T> Callable<T> whenReleased(CountDownLatch ready, CountDownLatch start, Callable<T> task) {
        return () -> {
            ready.countDown();
            await(start);
            return task.call();
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting on latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Fixture createFixture(LoanApplicationStatus initialStatus) {
        return TenantScopedExecution.callAsAdmin(() -> transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Lsp lsp = lspRepository.save(new Lsp("EVIDENCE-" + suffix, "Evidence LSP " + suffix, LspStatus.ACTIVE));
            LoanProduct product = loanProductRepository.save(new LoanProduct(
                    "EVIDENCE-" + suffix,
                    "Evidence product",
                    new BigDecimal("5000.00"),
                    new BigDecimal("250000.00"),
                    new BigDecimal("18.50"),
                    new BigDecimal("2.25"),
                    6,
                    24,
                    LoanProductStatus.ACTIVE
            ));
            LoanProductVersion version = loanProductVersionRepository.save(LoanProductVersionTestSupport.versionOne(product));
            mappingRepository.save(new LoanProductLspMapping(product, lsp, true));
            Borrower borrower = borrowerRepository.save(new Borrower(BorrowerProfile.builder()
                    .fullName("Evidence Borrower")
                    .emailAddress("evidence-" + suffix + "@example.com")
                    .mobileNumber("9876543210")
                    .dateOfBirth(LocalDate.of(1990, 1, 1))
                    .aadharNumber("123456789012")
                    .panNumber("ABCDE1234F")
                    .addressLine1("1 Test Street")
                    .addressCity("Mumbai")
                    .addressState("Maharashtra")
                    .addressZipcode("400001")
                    .employmentStatus("SALARIED")
                    .monthlyIncome(new BigDecimal("50000.00"))
                    .annualIncome(new BigDecimal("600000.00"))
                    .referencePersonName("Reference Person")
                    .referencePersonNumber("9876500000")
                    .build()));
            borrowerLspRelationshipRepository.saveAndFlush(new BorrowerLspRelationship(borrower, lsp, "API"));
            // Tenant-scoped reads of the borrower go through the V43/V45 access rows.
            jdbcTemplate.update("INSERT INTO borrower_lsp_access (borrower_id, lsp_id) VALUES (?, ?)",
                    borrower.getId(), lsp.getId());
            LoanApplication application = loanApplicationRepository.save(new LoanApplication(
                    borrower, lsp, product, version, "EVIDENCE-" + suffix, "API",
                    new BigDecimal("45000.00"), 12, initialStatus));
            documentChecklistService.seedDocumentChecklist(application, "test");
            return new Fixture(lsp.getId(), application.getId());
        }));
    }

    private record Fixture(UUID lspId, UUID applicationId) {
    }
}
