package com.bhawana.lms.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named borrower PII and employment fields shared by onboarding commands, entity construction, and merges.
 */
public record BorrowerProfile(
        String fullName,
        String emailAddress,
        String mobileNumber,
        LocalDate dateOfBirth,
        String gender,
        String maritalStatus,
        String fatherName,
        String aadharNumber,
        String panNumber,
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

    public static BorrowerProfile minimal(String fullName, String panNumber, String mobileNumber, String emailAddress) {
        return builder()
                .fullName(fullName)
                .panNumber(panNumber)
                .mobileNumber(mobileNumber)
                .emailAddress(emailAddress)
                .build();
    }

    public static BorrowerProfile fromEntity(Borrower borrower) {
        return builder()
                .fullName(borrower.getFullName())
                .emailAddress(borrower.getEmail())
                .mobileNumber(borrower.getMobile())
                .dateOfBirth(borrower.getDateOfBirth())
                .gender(borrower.getGender())
                .maritalStatus(borrower.getMaritalStatus())
                .fatherName(borrower.getFatherName())
                .aadharNumber(borrower.getAadharNumber())
                .panNumber(borrower.getPan())
                .addressLine1(borrower.getAddressLine1())
                .addressLine2(borrower.getAddressLine2())
                .addressCity(borrower.getCity())
                .addressState(borrower.getState())
                .addressZipcode(borrower.getAddressZipCode())
                .spouseName(borrower.getSpouseName())
                .employmentStatus(borrower.getEmploymentType())
                .organizationName(borrower.getOrganizationName())
                .empId(borrower.getEmployeeId())
                .employmentCity(borrower.getEmploymentCity())
                .employmentState(borrower.getEmploymentState())
                .employmentZip(borrower.getEmploymentZip())
                .monthlyIncome(borrower.getMonthlyIncome())
                .annualIncome(borrower.getAnnualIncome())
                .bankAccountNumber(borrower.getBankAccountNumber())
                .bankName(borrower.getBankName())
                .ifscCode(borrower.getIfscCode())
                .accountHolderName(borrower.getAccountHolderName())
                .referencePersonName(borrower.getReferencePersonName())
                .referencePersonNumber(borrower.getReferencePersonNumber())
                .build();
    }

    public BorrowerProfile withScaledIncomes(BigDecimal monthlyIncome, BigDecimal annualIncome) {
        return new BorrowerProfile(
                fullName,
                emailAddress,
                mobileNumber,
                dateOfBirth,
                gender,
                maritalStatus,
                fatherName,
                aadharNumber,
                panNumber,
                addressLine1,
                addressLine2,
                addressCity,
                addressState,
                addressZipcode,
                spouseName,
                employmentStatus,
                organizationName,
                empId,
                employmentCity,
                employmentState,
                employmentZip,
                monthlyIncome,
                annualIncome,
                bankAccountNumber,
                bankName,
                ifscCode,
                accountHolderName,
                referencePersonName,
                referencePersonNumber
        );
    }

    public BorrowerProfile withNormalizedIdentity(
            String panNumber,
            String mobileNumber,
            String fullName,
            String aadharNumber,
            String emailAddress
    ) {
        return new BorrowerProfile(
                fullName,
                emailAddress,
                mobileNumber,
                dateOfBirth,
                gender,
                maritalStatus,
                fatherName,
                aadharNumber,
                panNumber,
                addressLine1,
                addressLine2,
                addressCity,
                addressState,
                addressZipcode,
                spouseName,
                employmentStatus,
                organizationName,
                empId,
                employmentCity,
                employmentState,
                employmentZip,
                monthlyIncome,
                annualIncome,
                bankAccountNumber,
                bankName,
                ifscCode,
                accountHolderName,
                referencePersonName,
                referencePersonNumber
        );
    }

    public Map<String, Object> intakeAuditEntries() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("borrowerPan", panNumber);
        payload.put("borrowerFullName", fullName);
        payload.put("borrowerMobile", mobileNumber);
        payload.put("borrowerEmail", emailAddress);
        payload.put("borrowerDateOfBirth", dateOfBirth);
        payload.put("borrowerGender", gender);
        payload.put("borrowerMaritalStatus", maritalStatus);
        payload.put("borrowerFatherName", fatherName);
        payload.put("borrowerAadharNumber", aadharNumber);
        payload.put("addressLine1", addressLine1);
        payload.put("addressLine2", addressLine2);
        payload.put("borrowerCity", addressCity);
        payload.put("borrowerState", addressState);
        payload.put("addressZipCode", addressZipcode);
        payload.put("spouseName", spouseName);
        payload.put("borrowerEmploymentType", employmentStatus);
        payload.put("organizationName", organizationName);
        payload.put("employeeId", empId);
        payload.put("employmentCity", employmentCity);
        payload.put("employmentState", employmentState);
        payload.put("employmentZip", employmentZip);
        payload.put("borrowerMonthlyIncome", monthlyIncome);
        payload.put("borrowerAnnualIncome", annualIncome);
        payload.put("bankAccountNumber", bankAccountNumber);
        payload.put("bankName", bankName);
        payload.put("ifscCode", ifscCode);
        payload.put("accountHolderName", accountHolderName);
        payload.put("referencePersonName", referencePersonName);
        payload.put("referencePersonNumber", referencePersonNumber);
        return payload;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String fullName;
        private String emailAddress;
        private String mobileNumber;
        private LocalDate dateOfBirth;
        private String gender;
        private String maritalStatus;
        private String fatherName;
        private String aadharNumber;
        private String panNumber;
        private String addressLine1;
        private String addressLine2;
        private String addressCity;
        private String addressState;
        private String addressZipcode;
        private String spouseName;
        private String employmentStatus;
        private String organizationName;
        private String empId;
        private String employmentCity;
        private String employmentState;
        private String employmentZip;
        private BigDecimal monthlyIncome;
        private BigDecimal annualIncome;
        private String bankAccountNumber;
        private String bankName;
        private String ifscCode;
        private String accountHolderName;
        private String referencePersonName;
        private String referencePersonNumber;

        public Builder fullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        public Builder emailAddress(String emailAddress) {
            this.emailAddress = emailAddress;
            return this;
        }

        public Builder mobileNumber(String mobileNumber) {
            this.mobileNumber = mobileNumber;
            return this;
        }

        public Builder dateOfBirth(LocalDate dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
            return this;
        }

        public Builder gender(String gender) {
            this.gender = gender;
            return this;
        }

        public Builder maritalStatus(String maritalStatus) {
            this.maritalStatus = maritalStatus;
            return this;
        }

        public Builder fatherName(String fatherName) {
            this.fatherName = fatherName;
            return this;
        }

        public Builder aadharNumber(String aadharNumber) {
            this.aadharNumber = aadharNumber;
            return this;
        }

        public Builder panNumber(String panNumber) {
            this.panNumber = panNumber;
            return this;
        }

        public Builder addressLine1(String addressLine1) {
            this.addressLine1 = addressLine1;
            return this;
        }

        public Builder addressLine2(String addressLine2) {
            this.addressLine2 = addressLine2;
            return this;
        }

        public Builder addressCity(String addressCity) {
            this.addressCity = addressCity;
            return this;
        }

        public Builder addressState(String addressState) {
            this.addressState = addressState;
            return this;
        }

        public Builder addressZipcode(String addressZipcode) {
            this.addressZipcode = addressZipcode;
            return this;
        }

        public Builder spouseName(String spouseName) {
            this.spouseName = spouseName;
            return this;
        }

        public Builder employmentStatus(String employmentStatus) {
            this.employmentStatus = employmentStatus;
            return this;
        }

        public Builder organizationName(String organizationName) {
            this.organizationName = organizationName;
            return this;
        }

        public Builder empId(String empId) {
            this.empId = empId;
            return this;
        }

        public Builder employmentCity(String employmentCity) {
            this.employmentCity = employmentCity;
            return this;
        }

        public Builder employmentState(String employmentState) {
            this.employmentState = employmentState;
            return this;
        }

        public Builder employmentZip(String employmentZip) {
            this.employmentZip = employmentZip;
            return this;
        }

        public Builder monthlyIncome(BigDecimal monthlyIncome) {
            this.monthlyIncome = monthlyIncome;
            return this;
        }

        public Builder annualIncome(BigDecimal annualIncome) {
            this.annualIncome = annualIncome;
            return this;
        }

        public Builder bankAccountNumber(String bankAccountNumber) {
            this.bankAccountNumber = bankAccountNumber;
            return this;
        }

        public Builder bankName(String bankName) {
            this.bankName = bankName;
            return this;
        }

        public Builder ifscCode(String ifscCode) {
            this.ifscCode = ifscCode;
            return this;
        }

        public Builder accountHolderName(String accountHolderName) {
            this.accountHolderName = accountHolderName;
            return this;
        }

        public Builder referencePersonName(String referencePersonName) {
            this.referencePersonName = referencePersonName;
            return this;
        }

        public Builder referencePersonNumber(String referencePersonNumber) {
            this.referencePersonNumber = referencePersonNumber;
            return this;
        }

        public BorrowerProfile build() {
            return new BorrowerProfile(
                    fullName,
                    emailAddress,
                    mobileNumber,
                    dateOfBirth,
                    gender,
                    maritalStatus,
                    fatherName,
                    aadharNumber,
                    panNumber,
                    addressLine1,
                    addressLine2,
                    addressCity,
                    addressState,
                    addressZipcode,
                    spouseName,
                    employmentStatus,
                    organizationName,
                    empId,
                    employmentCity,
                    employmentState,
                    employmentZip,
                    monthlyIncome,
                    annualIncome,
                    bankAccountNumber,
                    bankName,
                    ifscCode,
                    accountHolderName,
                    referencePersonName,
                    referencePersonNumber
            );
        }
    }
}
