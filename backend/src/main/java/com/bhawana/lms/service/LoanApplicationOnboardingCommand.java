package com.bhawana.lms.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record LoanApplicationOnboardingCommand(
        UUID lspId,
        UUID productId,
        String loanProduct,
        String lspLoanId,
        String sourceChannel,
        String fullName,
        String emailAddress,
        String mobileNumber,
        LocalDate dob,
        String gender,
        String maritalStatus,
        String fatherName,
        String aadharNumber,
        String panNumber,
        BigDecimal loanAmount,
        BigDecimal interestRate,
        Integer loanTenure,
        String addressLine1,
        String addressLine2,
        String addressCity,
        String addressState,
        String addressZipcode,
        String spouseName,
        String employmentStatus,
        String organizationName,
        String empId,
        String employmentCity,
        String employmentState,
        String employmentZip,
        BigDecimal monthlyIncome,
        BigDecimal annualIncome,
        String bankAccountNumber,
        String bankName,
        String ifscCode,
        String accountHolderName,
        String referencePersonName,
        String referencePersonNumber
) {
}
