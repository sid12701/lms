package com.bhawana.lms.web;

import com.bhawana.lms.service.AdminReportingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/reports")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ReportAdminController {

    private final AdminReportingService adminReportingService;

    public ReportAdminController(AdminReportingService adminReportingService) {
        this.adminReportingService = adminReportingService;
    }

    @GetMapping(value = "/portfolio-mis", produces = "text/csv")
    public ResponseEntity<byte[]> downloadPortfolioMisReport(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) LocalDate disbursalDateFrom,
            @RequestParam(required = false) LocalDate disbursalDateTo
    ) {
        List<AdminReportingService.PortfolioMisRow> rows = adminReportingService.buildPortfolioMisReport(
                lspId,
                disbursalDateFrom,
                disbursalDateTo
        );

        String csv = buildPortfolioMisCsv(rows);
        String filename = "portfolio-mis-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
        MediaType contentType = new MediaType("text", "csv", StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(contentType)
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static String buildPortfolioMisCsv(List<AdminReportingService.PortfolioMisRow> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",",
                "LSP Code",
                "LSP Name",
                "Application ID",
                "External Loan ID",
                "Borrower Name",
                "Product Code",
                "Product Name",
                "Account Number",
                "Principal Amount",
                "Account Status",
                "Disbursal Date",
                "Delinquency Bucket",
                "Overdue Amount",
                "Closure Reason",
                "Closed Date"
        )).append('\n');

        for (AdminReportingService.PortfolioMisRow row : rows) {
            csv.append(toCsvCell(row.lspCode())).append(',')
                    .append(toCsvCell(row.lspName())).append(',')
                    .append(toCsvCell(row.applicationId())).append(',')
                    .append(toCsvCell(row.externalLoanId())).append(',')
                    .append(toCsvCell(row.borrowerFullName())).append(',')
                    .append(toCsvCell(row.productCode())).append(',')
                    .append(toCsvCell(row.productName())).append(',')
                    .append(toCsvCell(row.accountNumber())).append(',')
                    .append(toCsvCell(row.principalAmount())).append(',')
                    .append(toCsvCell(row.accountStatus())).append(',')
                    .append(toCsvCell(row.disbursalDate())).append(',')
                    .append(toCsvCell(row.delinquencyBucket())).append(',')
                    .append(toCsvCell(row.overdueAmount())).append(',')
                    .append(toCsvCell(row.closureReason())).append(',')
                    .append(toCsvCell(row.closedDate()))
                    .append('\n');
        }

        return csv.toString();
    }

    private static String toCsvCell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }

        String text = value.toString();
        if (!text.contains(",") && !text.contains("\"") && !text.contains("\n")) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
