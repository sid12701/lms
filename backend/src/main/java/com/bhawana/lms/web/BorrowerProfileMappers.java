package com.bhawana.lms.web;

import com.bhawana.lms.domain.BorrowerProfile;

public final class BorrowerProfileMappers {

    private BorrowerProfileMappers() {
    }

    public static BorrowerProfile fromOps(LoanApplicationOpsController.LoanApplicationRequest request) {
        return BorrowerProfile.builder()
                .fullName(request.borrowerFullName())
                .emailAddress(request.borrowerEmail())
                .mobileNumber(request.borrowerMobile())
                .dateOfBirth(request.borrowerDateOfBirth())
                .panNumber(request.borrowerPan())
                .addressCity(request.borrowerCity())
                .addressState(request.borrowerState())
                .employmentStatus(request.borrowerEmploymentType())
                .monthlyIncome(request.borrowerMonthlyIncome())
                .build();
    }

    public static BorrowerProfile fromLsp(LspLoanApplicationApiController.LspLoanApplicationRequest request) {
        return BorrowerProfile.builder()
                .fullName(request.fullName())
                .emailAddress(request.emailAddress())
                .mobileNumber(request.mobileNumber())
                .dateOfBirth(request.dob())
                .gender(request.gender())
                .maritalStatus(request.maritalStatus())
                .fatherName(request.fatherName())
                .aadharNumber(request.aadharNumber())
                .panNumber(request.panNumber())
                .addressLine1(request.addressLine1())
                .addressLine2(request.addressLine2())
                .addressCity(request.addressCity())
                .addressState(request.addressState())
                .addressZipcode(request.addressZipcode())
                .spouseName(request.spouseName())
                .employmentStatus(request.employmentStatus())
                .organizationName(request.organizationName())
                .empId(request.empId())
                .employmentCity(request.employmentCity())
                .employmentState(request.employmentState())
                .employmentZip(request.employmentZip())
                .monthlyIncome(request.monthlyIncome())
                .annualIncome(request.annualIncome())
                .bankAccountNumber(request.bankAccountNumber())
                .bankName(request.bankName())
                .ifscCode(request.ifscCode())
                .accountHolderName(request.accountHolderName())
                .referencePersonName(request.referencePersonName())
                .referencePersonNumber(request.referencePersonNumber())
                .build();
    }
}
