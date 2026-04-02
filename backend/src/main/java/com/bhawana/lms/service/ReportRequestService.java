package com.bhawana.lms.service;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.repo.ReportRequestRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportRequestService {

    private final ReportRequestRepository reportRequestRepository;
    private final AdminReportingService adminReportingService;

    public ReportRequestService(
            ReportRequestRepository reportRequestRepository,
            AdminReportingService adminReportingService
    ) {
        this.reportRequestRepository = reportRequestRepository;
        this.adminReportingService = adminReportingService;
    }

    @Transactional
    public ReportRequest createPortfolioMisRequest(
            UUID lspId,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            String requestedByUsername
    ) {
        validateDateRange(disbursalDateFrom, disbursalDateTo);
        ReportRequest reportRequest = new ReportRequest(
                ReportType.PORTFOLIO_MIS,
                adminReportingService.getOptionalLsp(lspId),
                disbursalDateFrom,
                disbursalDateTo,
                requestedByUsername
        );
        return reportRequestRepository.save(reportRequest);
    }

    @Transactional(readOnly = true)
    public List<ReportRequest> listRequests() {
        return reportRequestRepository.findTop50ByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public GeneratedStoredReport getCompletedReport(UUID requestId) {
        ReportRequest reportRequest = reportRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown report request id: " + requestId));
        if (reportRequest.getStatus() != ReportRequestStatus.COMPLETED || reportRequest.getReportContent() == null) {
            throw new IllegalArgumentException("Report is not ready for download.");
        }

        return new GeneratedStoredReport(
                reportRequest.getFileName(),
                reportRequest.getMediaType(),
                reportRequest.getReportContent().getBytes(StandardCharsets.UTF_8)
        );
    }

    @Transactional
    public ProcessingSummary processPendingRequests(int batchSize) {
        List<ReportRequest> pendingRequests = reportRequestRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(ReportRequestStatus.PENDING),
                PageRequest.of(0, batchSize)
        );

        int completed = 0;
        int failed = 0;

        for (ReportRequest reportRequest : pendingRequests) {
            reportRequest.markProcessing();
            reportRequestRepository.save(reportRequest);

            try {
                AdminReportingService.GeneratedReport generatedReport = switch (reportRequest.getReportType()) {
                    case PORTFOLIO_MIS -> adminReportingService.generatePortfolioMisCsv(
                            reportRequest.getLsp() == null ? null : reportRequest.getLsp().getId(),
                            reportRequest.getDisbursalDateFrom(),
                            reportRequest.getDisbursalDateTo()
                    );
                };

                reportRequest.markCompleted(
                        generatedReport.fileName(),
                        generatedReport.mediaType(),
                        new String(generatedReport.content(), StandardCharsets.UTF_8),
                        Instant.now()
                );
                completed += 1;
            } catch (RuntimeException exception) {
                reportRequest.markFailed(exception.getMessage());
                failed += 1;
            }

            reportRequestRepository.save(reportRequest);
        }

        return new ProcessingSummary(pendingRequests.size(), completed, failed);
    }

    private static void validateDateRange(LocalDate disbursalDateFrom, LocalDate disbursalDateTo) {
        if (disbursalDateFrom != null && disbursalDateTo != null && disbursalDateFrom.isAfter(disbursalDateTo)) {
            throw new IllegalArgumentException("disbursalDateFrom cannot be after disbursalDateTo.");
        }
    }

    public record GeneratedStoredReport(
            String fileName,
            String mediaType,
            byte[] content
    ) {
    }

    public record ProcessingSummary(
            int processed,
            int completed,
            int failed
    ) {
    }
}
