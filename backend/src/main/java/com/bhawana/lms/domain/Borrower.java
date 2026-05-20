package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "borrower")
public class Borrower {

    @Id
    private UUID id;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "borrower_lsp_access", joinColumns = @jakarta.persistence.JoinColumn(name = "borrower_id"))
    @Column(name = "lsp_id", nullable = false)
    private Set<UUID> visibleLspIds = new LinkedHashSet<>();

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(nullable = false, length = 10)
    private String pan;

    @Column(nullable = false, length = 32)
    private String mobile;

    @Column(length = 255)
    private String email;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 32)
    private String gender;

    @Column(name = "marital_status", length = 32)
    private String maritalStatus;

    @Column(name = "father_name", length = 255)
    private String fatherName;

    @Column(name = "aadhar_number", length = 16)
    private String aadharNumber;

    @Column(length = 128)
    private String city;

    @Column(length = 128)
    private String state;

    @Column(name = "address_line_1", length = 255)
    private String addressLine1;

    @Column(name = "address_line_2", length = 255)
    private String addressLine2;

    @Column(name = "address_zip_code", length = 16)
    private String addressZipCode;

    @Column(name = "spouse_name", length = 255)
    private String spouseName;

    @Column(name = "employment_type", length = 64)
    private String employmentType;

    @Column(name = "organization_name", length = 255)
    private String organizationName;

    @Column(name = "employee_id", length = 128)
    private String employeeId;

    @Column(name = "employment_city", length = 128)
    private String employmentCity;

    @Column(name = "employment_state", length = 128)
    private String employmentState;

    @Column(name = "employment_zip", length = 16)
    private String employmentZip;

    @Column(name = "monthly_income", precision = 19, scale = 2)
    private BigDecimal monthlyIncome;

    @Column(name = "annual_income", precision = 19, scale = 2)
    private BigDecimal annualIncome;

    @Column(name = "bank_account_number", length = 64)
    private String bankAccountNumber;

    @Column(name = "bank_name", length = 255)
    private String bankName;

    @Column(name = "ifsc_code", length = 32)
    private String ifscCode;

    @Column(name = "account_holder_name", length = 255)
    private String accountHolderName;

    @Column(name = "reference_person_name", length = 255)
    private String referencePersonName;

    @Column(name = "reference_person_number", length = 32)
    private String referencePersonNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Borrower() {
    }

    public Borrower(String fullName, String pan, String mobile, String email) {
        this(
                fullName,
                pan,
                mobile,
                email,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public Borrower(Lsp lsp, String fullName, String pan, String mobile, String email) {
        this(fullName, pan, mobile, email);
        grantVisibilityTo(lsp);
    }

    public Borrower(
            String fullName,
            String pan,
            String mobile,
            String email,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus,
            String fatherName,
            String aadharNumber,
            String city,
            String state,
            String addressLine1,
            String addressLine2,
            String addressZipCode,
            String spouseName,
            String employmentType,
            String organizationName,
            String employeeId,
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
        this.id = UUID.randomUUID();
        this.fullName = fullName.trim();
        this.pan = pan.trim().toUpperCase();
        this.mobile = mobile.trim();
        this.email = normalizeEmail(email);
        this.dateOfBirth = dateOfBirth;
        this.gender = normalizeCodedValue(gender);
        this.maritalStatus = normalizeCodedValue(maritalStatus);
        this.fatherName = normalizeOptional(fatherName);
        this.aadharNumber = normalizeAadharNumber(aadharNumber);
        this.city = normalizeOptional(city);
        this.state = normalizeOptional(state);
        this.addressLine1 = normalizeOptional(addressLine1);
        this.addressLine2 = normalizeOptional(addressLine2);
        this.addressZipCode = normalizeOptional(addressZipCode);
        this.spouseName = normalizeOptional(spouseName);
        this.employmentType = normalizeEmploymentType(employmentType);
        this.organizationName = normalizeOptional(organizationName);
        this.employeeId = normalizeOptional(employeeId);
        this.employmentCity = normalizeOptional(employmentCity);
        this.employmentState = normalizeOptional(employmentState);
        this.employmentZip = normalizeOptional(employmentZip);
        this.monthlyIncome = normalizeMonthlyIncome(monthlyIncome);
        this.annualIncome = normalizeMonthlyIncome(annualIncome);
        this.bankAccountNumber = normalizeOptional(bankAccountNumber);
        this.bankName = normalizeOptional(bankName);
        this.ifscCode = normalizeCodedValue(ifscCode);
        this.accountHolderName = normalizeOptional(accountHolderName);
        this.referencePersonName = normalizeOptional(referencePersonName);
        this.referencePersonNumber = normalizeOptional(referencePersonNumber);
    }

    public Borrower(
            Lsp lsp,
            String fullName,
            String pan,
            String mobile,
            String email,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus,
            String fatherName,
            String aadharNumber,
            String city,
            String state,
            String addressLine1,
            String addressLine2,
            String addressZipCode,
            String spouseName,
            String employmentType,
            String organizationName,
            String employeeId,
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
        this(
                fullName,
                pan,
                mobile,
                email,
                dateOfBirth,
                gender,
                maritalStatus,
                fatherName,
                aadharNumber,
                city,
                state,
                addressLine1,
                addressLine2,
                addressZipCode,
                spouseName,
                employmentType,
                organizationName,
                employeeId,
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
        grantVisibilityTo(lsp);
    }

    public Borrower(
            Lsp lsp,
            String fullName,
            String pan,
            String mobile,
            String email,
            LocalDate dateOfBirth,
            String city,
            String state,
            String employmentType,
            BigDecimal monthlyIncome
    ) {
        this(
                lsp,
                fullName,
                pan,
                mobile,
                email,
                dateOfBirth,
                null,
                null,
                null,
                null,
                city,
                state,
                null,
                null,
                null,
                null,
                employmentType,
                null,
                null,
                null,
                null,
                null,
                monthlyIncome,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Set<UUID> getVisibleLspIds() {
        return Collections.unmodifiableSet(visibleLspIds);
    }

    public boolean hasVisibilityFor(UUID lspId) {
        return lspId != null && visibleLspIds.contains(lspId);
    }

    public void grantVisibilityTo(Lsp lsp) {
        if (lsp != null) {
            grantVisibilityTo(lsp.getId());
        }
    }

    public void grantVisibilityTo(UUID lspId) {
        if (lspId != null) {
            visibleLspIds.add(lspId);
        }
    }

    public String getFullName() {
        return fullName;
    }

    public String getPan() {
        return pan;
    }

    public String getMobile() {
        return mobile;
    }

    public String getEmail() {
        return email;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getGender() {
        return gender;
    }

    public String getMaritalStatus() {
        return maritalStatus;
    }

    public String getFatherName() {
        return fatherName;
    }

    public String getAadharNumber() {
        return aadharNumber;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public String getAddressZipCode() {
        return addressZipCode;
    }

    public String getSpouseName() {
        return spouseName;
    }

    public String getEmploymentType() {
        return employmentType;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getEmploymentCity() {
        return employmentCity;
    }

    public String getEmploymentState() {
        return employmentState;
    }

    public String getEmploymentZip() {
        return employmentZip;
    }

    public BigDecimal getMonthlyIncome() {
        return monthlyIncome;
    }

    public BigDecimal getAnnualIncome() {
        return annualIncome;
    }

    public String getBankAccountNumber() {
        return bankAccountNumber;
    }

    public String getBankName() {
        return bankName;
    }

    public String getIfscCode() {
        return ifscCode;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public String getReferencePersonName() {
        return referencePersonName;
    }

    public String getReferencePersonNumber() {
        return referencePersonNumber;
    }

    public void refreshProfile(
            String fullName,
            String mobile,
            String email
    ) {
        refreshProfile(
                fullName,
                mobile,
                email,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public void refreshProfile(
            String fullName,
            String mobile,
            String email,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus,
            String fatherName,
            String aadharNumber,
            String city,
            String state,
            String addressLine1,
            String addressLine2,
            String addressZipCode,
            String spouseName,
            String employmentType,
            String organizationName,
            String employeeId,
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
        this.fullName = fullName.trim();
        this.mobile = mobile.trim();
        this.email = normalizeEmail(email);
        this.dateOfBirth = dateOfBirth;
        this.gender = normalizeCodedValue(gender);
        this.maritalStatus = normalizeCodedValue(maritalStatus);
        this.fatherName = normalizeOptional(fatherName);
        this.aadharNumber = normalizeAadharNumber(aadharNumber);
        this.city = normalizeOptional(city);
        this.state = normalizeOptional(state);
        this.addressLine1 = normalizeOptional(addressLine1);
        this.addressLine2 = normalizeOptional(addressLine2);
        this.addressZipCode = normalizeOptional(addressZipCode);
        this.spouseName = normalizeOptional(spouseName);
        this.employmentType = normalizeEmploymentType(employmentType);
        this.organizationName = normalizeOptional(organizationName);
        this.employeeId = normalizeOptional(employeeId);
        this.employmentCity = normalizeOptional(employmentCity);
        this.employmentState = normalizeOptional(employmentState);
        this.employmentZip = normalizeOptional(employmentZip);
        this.monthlyIncome = normalizeMonthlyIncome(monthlyIncome);
        this.annualIncome = normalizeMonthlyIncome(annualIncome);
        this.bankAccountNumber = normalizeOptional(bankAccountNumber);
        this.bankName = normalizeOptional(bankName);
        this.ifscCode = normalizeCodedValue(ifscCode);
        this.accountHolderName = normalizeOptional(accountHolderName);
        this.referencePersonName = normalizeOptional(referencePersonName);
        this.referencePersonNumber = normalizeOptional(referencePersonNumber);
    }

    public void mergeLatestProfile(
            String fullName,
            String mobile,
            String email,
            LocalDate dateOfBirth,
            String gender,
            String maritalStatus,
            String fatherName,
            String aadharNumber,
            String city,
            String state,
            String addressLine1,
            String addressLine2,
            String addressZipCode,
            String spouseName,
            String employmentType,
            String organizationName,
            String employeeId,
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
        refreshProfile(
                fullName,
                mobile,
                email,
                dateOfBirth,
                gender,
                maritalStatus,
                fatherName,
                this.aadharNumber == null ? aadharNumber : this.aadharNumber,
                city,
                state,
                addressLine1,
                addressLine2,
                addressZipCode,
                spouseName,
                employmentType,
                organizationName,
                employeeId,
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

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeEmploymentType(String employmentType) {
        String normalized = normalizeOptional(employmentType);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private static String normalizeCodedValue(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private static String normalizeAadharNumber(String aadharNumber) {
        String normalized = normalizeOptional(aadharNumber);
        return normalized == null ? null : normalized.replace(" ", "");
    }

    private static BigDecimal normalizeMonthlyIncome(BigDecimal monthlyIncome) {
        if (monthlyIncome == null) {
            return null;
        }

        return monthlyIncome.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
