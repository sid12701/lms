package com.bhawana.lms.service;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.repo.ReportRequestRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "app.reports.notifications.enabled=false")
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ReportRequestStorageFailureTest {

    @Autowired
    private ReportRequestService reportRequestService;

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @MockitoBean
    private ReportStorageService reportStorageService;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @BeforeEach
    void resetReports() {
        reportRequestRepository.deleteAll();
    }

    @Test
    void storageUploadFailureMarksRequestFailed() {
        when(reportStorageService.store(any(), any()))
                .thenThrow(new RuntimeException("simulated R2 outage"));

        ReportRequest pending = reportRequestRepository.save(new ReportRequest(
                ReportType.PORTFOLIO_MIS,
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "ops.admin",
                "reports@example.com"
        ));

        reportRequestService.processPendingRequests(10);

        ReportRequest reloaded = reportRequestRepository.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReportRequestStatus.FAILED);
        assertThat(reloaded.getStorageKey()).isNull();
        assertThat(reloaded.getFileName()).isNull();
        assertThat(reloaded.getErrorMessage()).contains("simulated R2 outage");

        assertThatThrownBy(() -> reportRequestService.getCompletedReport(reloaded.getId()))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("not ready");
    }
}
