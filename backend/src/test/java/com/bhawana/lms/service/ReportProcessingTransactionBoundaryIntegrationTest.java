package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.repo.ReportRequestRepository;
import com.bhawana.lms.support.InMemoryReportStorageConfig;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * H24 regression tests: report generation, object storage and SMTP run outside every database
 * transaction; the claim and the outcome record are short fenced transactions; a crashed claim
 * is recovered through its lease; notifications are durable, claim-guarded and at-least-once.
 */
@SpringBootTest(properties = "app.reports.notifications.enabled=true")
@ActiveProfiles("test")
@Import(InMemoryReportStorageConfig.class)
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ReportProcessingTransactionBoundaryIntegrationTest {

    @Autowired
    private ReportRequestService reportRequestService;

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private InMemoryReportStorageConfig.InMemoryReportStorageService storage() {
        return (InMemoryReportStorageConfig.InMemoryReportStorageService) reportStorageService;
    }

    @MockitoSpyBean
    private ReportStorageService reportStorageService;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void resetReports() {
        reportRequestRepository.deleteAll();
    }

    @Test
    void storageAndSmtpRunOutsideTransactionsWhileTheClaimIsAlreadyCommitted() {
        AtomicBoolean storeHadTransaction = new AtomicBoolean(true);
        AtomicBoolean sendHadTransaction = new AtomicBoolean(true);
        AtomicBoolean claimDurableDuringUpload = new AtomicBoolean(false);
        ReportRequest saved = saveRequest("ops.admin");
        doAnswer(invocation -> {
            storeHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            // Read on a separate connection: if the claim transaction were still open during
            // the upload, the row would still read PENDING here.
            String status = jdbcTemplate.queryForObject(
                    "select status from report_request where id = ?",
                    String.class,
                    saved.getId()
            );
            claimDurableDuringUpload.set("PROCESSING".equals(status));
            return invocation.callRealMethod();
        }).when(reportStorageService).store(any(), any());
        doAnswer(invocation -> {
            sendHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(javaMailSender).send(any(SimpleMailMessage.class));

        reportRequestService.processPendingRequests(10);

        ReportRequest reloaded = reload(saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ReportRequestStatus.COMPLETED);
        assertThat(reloaded.getStorageKey()).startsWith("reports/" + saved.getId() + "/");
        assertThat(reloaded.getNotificationSentAt()).isNotNull();
        assertThat(storeHadTransaction).as("storage upload ran inside a transaction").isFalse();
        assertThat(sendHadTransaction).as("SMTP send ran inside a transaction").isFalse();
        assertThat(claimDurableDuringUpload)
                .as("claim was not yet committed while the upload ran")
                .isTrue();
    }

    @Test
    void crashedClaimIsReclaimedAndRetryConvergesOnOneObject() {
        ReportRequest saved = saveRequest("ops.admin");
        // Simulate a worker that claimed, uploaded, then died before recording the outcome.
        saved.claimProcessing("dead-worker", Instant.now().minus(10, ChronoUnit.MINUTES));
        reportRequestRepository.saveAndFlush(saved);

        ReportRequest reloaded = reload(saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ReportRequestStatus.PROCESSING);
        assertThat(reloaded.getProcessingOwner()).isEqualTo("dead-worker");

        reportRequestService.processPendingRequests(10);

        reloaded = reload(saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ReportRequestStatus.COMPLETED);
        assertThat(reloaded.getStorageKey()).isNotNull();

        // The reclaim regenerated and stored the same deterministic key: exactly one object
        // exists for this request (the map is shared across tests, so filter by prefix).
        assertThat(storage().storedKeys().stream()
                .filter(key -> key.startsWith("reports/" + saved.getId() + "/")))
                .containsExactly(reloaded.getStorageKey());

        // A terminal row is never reclaimed for another generation pass.
        reportRequestService.processPendingRequests(10);
        assertThat(storage().storedKeys().stream()
                .filter(key -> key.startsWith("reports/" + saved.getId() + "/")))
                .hasSize(1);
    }

    @Test
    void oneFailedRequestDoesNotPoisonTheBatch() {
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failFirst.getAndSet(false)) {
                throw new RuntimeException("simulated R2 outage");
            }
            return invocation.callRealMethod();
        }).when(reportStorageService).store(any(), any());

        ReportRequest doomed = saveRequest("ops.unlucky");
        ReportRequest healthy = saveRequest("ops.lucky");

        reportRequestService.processPendingRequests(10);

        ReportRequest reloadedDoomed = reload(doomed.getId());
        ReportRequest reloadedHealthy = reload(healthy.getId());
        assertThat(reloadedDoomed.getStatus()).isEqualTo(ReportRequestStatus.FAILED);
        assertThat(reloadedDoomed.getErrorMessage()).contains("simulated R2 outage");
        assertThat(reloadedDoomed.getStorageKey()).isNull();
        assertThat(reloadedHealthy.getStatus()).isEqualTo(ReportRequestStatus.COMPLETED);
        assertThat(reloadedHealthy.getStorageKey()).isNotNull();
    }

    @Test
    void failedNotificationRetriesWithoutRegeneratingTheReport() throws Exception {
        doThrow(new MailSendException("smtp down"))
                .doNothing()
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        ReportRequest saved = saveRequest("ops.admin");
        reportRequestService.processPendingRequests(10);

        ReportRequest reloaded = reload(saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ReportRequestStatus.COMPLETED);
        assertThat(reloaded.getNotificationSentAt()).isNull();
        assertThat(reloaded.getNotificationErrorMessage()).contains("smtp down");
        assertThat(reloaded.getNotificationAttempts()).isEqualTo(1);

        // The durable retry path: a later sweep claims the pending row and sends again.
        int attempted = reportRequestService.processPendingNotifications(10);

        assertThat(attempted).isEqualTo(1);
        reloaded = reload(saved.getId());
        assertThat(reloaded.getNotificationSentAt()).isNotNull();
        assertThat(reloaded.getNotificationErrorMessage()).isNull();
        assertThat(reloaded.getNotificationAttempts()).isEqualTo(2);
        verify(javaMailSender, times(2)).send(any(SimpleMailMessage.class));
        verify(reportStorageService, times(1)).store(any(), any());
    }

    @Test
    void storageFailureCleansUpTheTemporaryExportFile() throws java.io.IOException {
        doThrow(new RuntimeException("simulated R2 outage"))
                .when(reportStorageService).store(any(), any());
        java.nio.file.Path tempDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        java.util.Set<String> before = reportTempFiles(tempDir);

        ReportRequest saved = saveRequest("ops.admin");
        reportRequestService.processPendingRequests(10);

        assertThat(reload(saved.getId()).getStatus()).isEqualTo(ReportRequestStatus.FAILED);
        assertThat(reportTempFiles(tempDir)).isEqualTo(before);
    }

    private static java.util.Set<String> reportTempFiles(java.nio.file.Path tempDir)
            throws java.io.IOException {
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(tempDir)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("report-request-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void liveLeaseOwnedByAnotherWorkerIsNotTouched() {
        ReportRequest saved = saveRequest("ops.admin");
        saved.claimProcessing("other-worker", Instant.now().plus(10, ChronoUnit.MINUTES));
        reportRequestRepository.saveAndFlush(saved);

        reportRequestService.processPendingRequests(10);

        ReportRequest reloaded = reload(saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ReportRequestStatus.PROCESSING);
        assertThat(reloaded.getProcessingOwner()).isEqualTo("other-worker");
        verify(reportStorageService, times(0)).store(any(), any());
    }

    @Test
    void pendingNotificationIsSkippedWhileAnotherWorkerHoldsTheLease() {
        ReportRequest saved = saveRequest("ops.admin");
        saved.claimProcessing("seed-worker", Instant.now().plusSeconds(60));
        reportRequestRepository.saveAndFlush(saved);
        int recorded = reportRequestRepository.recordOutcomeIfOwned(
                saved.getId(),
                saved.getProcessingAttempt(),
                "seed-worker",
                ReportRequestStatus.COMPLETED,
                "report.csv",
                "text/csv",
                "reports/" + saved.getId() + "/portfolio_mis/report.csv",
                null,
                Instant.now(),
                ReportRequestStatus.PROCESSING,
                Instant.now()
        );
        assertThat(recorded).isEqualTo(1);
        // Another worker owns the notification lease.
        assertThat(reportRequestRepository.tryClaimNotification(
                saved.getId(),
                "other-worker",
                Instant.now().plus(10, ChronoUnit.MINUTES),
                List.of(ReportRequestStatus.COMPLETED, ReportRequestStatus.FAILED),
                Instant.now()
        )).isEqualTo(1);

        int attempted = reportRequestService.processPendingNotifications(10);
        assertThat(attempted).isEqualTo(0);
        verify(javaMailSender, times(0)).send(any(SimpleMailMessage.class));

        // Once that lease lapses, the durable sweep claims and delivers it.
        jdbcTemplate.update(
                "update report_request set processing_expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)),
                saved.getId()
        );

        attempted = reportRequestService.processPendingNotifications(10);
        assertThat(attempted).isEqualTo(1);
        verify(javaMailSender, times(1)).send(any(SimpleMailMessage.class));
        assertThat(reload(saved.getId()).getNotificationSentAt()).isNotNull();
    }

    private ReportRequest saveRequest(String requestedByUsername) {
        return reportRequestRepository.saveAndFlush(new ReportRequest(
                ReportType.PORTFOLIO_MIS,
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                requestedByUsername,
                requestedByUsername + "@example.com"
        ));
    }

    private ReportRequest reload(UUID id) {
        return reportRequestRepository.findById(id).orElseThrow();
    }
}
