package com.bhawana.lms.service;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class ReportNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ReportNotificationService.class);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JavaMailSender mailSender;
    private final boolean notificationsEnabled;
    private final String fromAddress;
    private final String reportsPageUrl;

    public ReportNotificationService(
            JavaMailSender mailSender,
            @Value("${app.reports.notifications.enabled:true}") boolean notificationsEnabled,
            @Value("${app.reports.notifications.from-address:}") String fromAddress,
            @Value("${app.reports.notifications.reports-page-url:}") String reportsPageUrl
    ) {
        this.mailSender = mailSender;
        this.notificationsEnabled = notificationsEnabled;
        this.fromAddress = fromAddress == null ? "" : fromAddress.trim();
        this.reportsPageUrl = reportsPageUrl == null ? "" : reportsPageUrl.trim();
    }

    public NotificationResult sendTerminalStatusNotification(ReportRequest reportRequest) {
        if (!notificationsEnabled || reportRequest.getNotificationEmail() == null) {
            return NotificationResult.skipped();
        }

        if (reportRequest.getStatus() != ReportRequestStatus.COMPLETED
                && reportRequest.getStatus() != ReportRequestStatus.FAILED) {
            return NotificationResult.skipped();
        }

        SimpleMailMessage message = new SimpleMailMessage();
        if (!fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(reportRequest.getNotificationEmail());
        message.setSubject(buildSubject(reportRequest));
        message.setText(buildBody(reportRequest));

        try {
            mailSender.send(message);
            return NotificationResult.sent(Instant.now());
        } catch (MailException exception) {
            log.warn(
                    "Unable to send notification for report request {} to {}",
                    reportRequest.getId(),
                    reportRequest.getNotificationEmail(),
                    exception
            );
            return NotificationResult.failed(exception.getMessage());
        }
    }

    private String buildSubject(ReportRequest reportRequest) {
        return switch (reportRequest.getStatus()) {
            case COMPLETED -> "Portfolio MIS report ready";
            case FAILED -> "Portfolio MIS report failed";
            default -> "Report request update";
        };
    }

    private String buildBody(ReportRequest reportRequest) {
        StringBuilder body = new StringBuilder();
        body.append("Report type: ").append(reportRequest.getReportType().name()).append('\n');
        body.append("Request id: ").append(reportRequest.getId()).append('\n');
        body.append("Requested by: ").append(reportRequest.getRequestedByUsername()).append('\n');
        body.append("Requested at: ").append(format(reportRequest.getCreatedAt())).append('\n');
        body.append("Tenant: ").append(reportRequest.getLsp() == null ? "All LSPs" : reportRequest.getLsp().getName()).append('\n');
        body.append("Disbursal date range: ")
                .append(reportRequest.getDisbursalDateFrom() == null ? "Start" : reportRequest.getDisbursalDateFrom())
                .append(" to ")
                .append(reportRequest.getDisbursalDateTo() == null ? "End" : reportRequest.getDisbursalDateTo())
                .append('\n');
        body.append("Status: ").append(reportRequest.getStatus().name()).append('\n');

        if (reportRequest.getStatus() == ReportRequestStatus.COMPLETED) {
            body.append("Generated file: ").append(reportRequest.getFileName()).append('\n');
            if (!reportsPageUrl.isBlank()) {
                body.append("Open reports: ").append(reportsPageUrl).append('\n');
            }
        } else if (reportRequest.getErrorMessage() != null) {
            body.append("Failure reason: ").append(reportRequest.getErrorMessage()).append('\n');
        }

        return body.toString();
    }

    private static String format(Instant instant) {
        if (instant == null) {
            return "";
        }
        return TIMESTAMP_FORMATTER.format(instant.atOffset(ZoneOffset.UTC));
    }

    public record NotificationResult(
            boolean attempted,
            Instant sentAt,
            String errorMessage
    ) {

        public static NotificationResult skipped() {
            return new NotificationResult(false, null, null);
        }

        public static NotificationResult sent(Instant sentAt) {
            return new NotificationResult(true, sentAt, null);
        }

        public static NotificationResult failed(String errorMessage) {
            return new NotificationResult(true, null, errorMessage);
        }
    }
}
