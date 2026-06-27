package com.bhawana.lms.service;

import com.bhawana.lms.service.AdminReportingService.InstallmentSnapshot;
import com.bhawana.lms.service.AdminReportingService.PortfolioMisRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders portfolio MIS rows to CSV: a fixed column set followed by dynamic per-installment EMI
 * columns sized to the widest row. Pure serialization — the row aggregation lives in
 * {@link AdminReportingService}.
 */
final class PortfolioMisCsvWriter {

    private PortfolioMisCsvWriter() {
    }

    static String toCsv(List<PortfolioMisRow> rows) {
        // First pass: determine max installments across all rows
        int maxInstallments = rows.stream()
                .mapToInt(row -> row.installments() == null ? 0 : row.installments().size())
                .max()
                .orElse(0);

        StringBuilder csv = new StringBuilder();

        // Fixed header columns
        List<String> headerColumns = new ArrayList<>(List.of(
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
                "Closed Date",
                "Application Created At",
                "Loan Year",
                "Processing Fee",
                "Disbursal Amount",
                "Net Disbursed Amount",
                "Interest Rate",
                "Tenure Months",
                "Borrower ID",
                "Per EMI Amount",
                "Loan Status",
                "Foreclosed Repaid Amount",
                "Foreclosure Date",
                "Normal Closure Date",
                "Days Past Due",
                "Customer Name",
                "Address",
                "Zip Code",
                "State",
                "IFSC Code",
                "Bank Account Number",
                "Gender",
                "Aadhar Number",
                "PAN Number",
                "Profession",
                "Income"
        ));

        // Dynamic EMI columns
        for (int i = 1; i <= maxInstallments; i++) {
            headerColumns.add("EMI " + i + " Due Date");
            headerColumns.add("EMI " + i + " Amount");
            headerColumns.add("EMI " + i + " Paid Amount");
            headerColumns.add("EMI " + i + " Received");
        }

        csv.append(String.join(",", headerColumns)).append('\n');

        for (PortfolioMisRow row : rows) {
            // Fixed columns
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
                    .append(toCsvCell(row.closedDate())).append(',')
                    .append(toCsvCell(row.applicationCreatedAt())).append(',')
                    .append(toCsvCell(row.loanYear())).append(',')
                    .append(toCsvCell(row.processingFeeAmount())).append(',')
                    .append(toCsvCell(row.disbursalAmount())).append(',')
                    .append(toCsvCell(row.netDisbursedAmount())).append(',')
                    .append(toCsvCell(row.interestRate())).append(',')
                    .append(toCsvCell(row.tenureMonths())).append(',')
                    .append(toCsvCell(row.borrowerId())).append(',')
                    .append(toCsvCell(row.perEmiAmount())).append(',')
                    .append(toCsvCell(row.loanStatusDisplay())).append(',')
                    .append(toCsvCell(row.foreclosedRepaidAmount())).append(',')
                    .append(toCsvCell(row.foreclosureDate())).append(',')
                    .append(toCsvCell(row.normalClosureDate())).append(',')
                    .append(toCsvCell(row.daysPastDue())).append(',')
                    .append(toCsvCell(row.customerName())).append(',')
                    .append(toCsvCell(row.address())).append(',')
                    .append(toCsvCell(row.zipCode())).append(',')
                    .append(toCsvCell(row.borrowerState())).append(',')
                    .append(toCsvCell(row.ifscCode())).append(',')
                    .append(toCsvCell(row.bankAccountNumber())).append(',')
                    .append(toCsvCell(row.gender())).append(',')
                    .append(toCsvCell(row.aadharNumber())).append(',')
                    .append(toCsvCell(row.panNumber())).append(',')
                    .append(toCsvCell(row.profession())).append(',')
                    .append(toCsvCell(row.income()));

            // Dynamic EMI columns
            List<InstallmentSnapshot> installmentList = row.installments() != null
                    ? row.installments()
                    : Collections.emptyList();
            for (int i = 0; i < maxInstallments; i++) {
                if (i < installmentList.size()) {
                    InstallmentSnapshot snap = installmentList.get(i);
                    csv.append(',').append(toCsvCell(snap.dueDate()))
                            .append(',').append(toCsvCell(snap.installmentAmount()))
                            .append(',').append(toCsvCell(snap.paidAmount()))
                            .append(',').append(toCsvCell(snap.received() ? "Yes" : "No"));
                } else {
                    csv.append(",,,," );
                }
            }

            csv.append('\n');
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
