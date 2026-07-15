package com.bhawana.lms.domain;

import com.bhawana.lms.common.util.Strings;
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

    @ElementCollection(fetch = FetchType.LAZY)
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

    @Column(name = "aadhar_number", length = 12)
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

    @Column(name = "ifsc_code", length = 11)
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
        this(BorrowerProfile.minimal(fullName, pan, mobile, email));
    }

    public Borrower(BorrowerProfile profile) {
        this.id = UUID.randomUUID();
        applyProfile(profile);
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

    /**
     * Mutates only the legacy {@code borrower_lsp_access} collection. Callers outside
     * this package must use {@link com.bhawana.lms.service.BorrowerLspRelationshipService#grantVisibility}
     * so the Spec S19 relationship row is dual-written.
     */
    void addVisibleLspId(UUID lspId) {
        if (lspId != null) {
            visibleLspIds.add(lspId);
        }
    }

    public void updateBankDetails(
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String accountHolderName
    ) {
        this.bankAccountNumber = Strings.normalizeOptional(bankAccountNumber);
        this.bankName = Strings.normalizeOptional(bankName);
        this.ifscCode = normalizeCodedValue(ifscCode);
        this.accountHolderName = Strings.normalizeOptional(accountHolderName);
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

    public void refreshProfile(String fullName, String mobile, String email) {
        this.fullName = fullName.trim();
        this.mobile = mobile.trim();
        this.email = normalizeEmail(email);
    }

    public void refreshProfile(BorrowerProfile profile) {
        applyProfile(profile);
    }

    public void mergeLatestProfile(BorrowerProfile profile) {
        BorrowerProfile merged = new BorrowerProfile(
                profile.fullName(),
                profile.emailAddress(),
                profile.mobileNumber(),
                profile.dateOfBirth(),
                profile.gender(),
                profile.maritalStatus(),
                profile.fatherName(),
                this.aadharNumber == null ? profile.aadharNumber() : this.aadharNumber,
                profile.panNumber(),
                profile.addressLine1(),
                profile.addressLine2(),
                profile.addressCity(),
                profile.addressState(),
                profile.addressZipcode(),
                profile.spouseName(),
                profile.employmentStatus(),
                profile.organizationName(),
                profile.empId(),
                profile.employmentCity(),
                profile.employmentState(),
                profile.employmentZip(),
                profile.monthlyIncome(),
                profile.annualIncome(),
                profile.bankAccountNumber(),
                profile.bankName(),
                profile.ifscCode(),
                profile.accountHolderName(),
                profile.referencePersonName(),
                profile.referencePersonNumber()
        );
        applyProfile(merged);
    }

    private void applyProfile(BorrowerProfile profile) {
        this.fullName = profile.fullName().trim();
        this.pan = profile.panNumber().trim().toUpperCase();
        this.mobile = profile.mobileNumber().trim();
        this.email = normalizeEmail(profile.emailAddress());
        this.dateOfBirth = profile.dateOfBirth();
        this.gender = normalizeCodedValue(profile.gender());
        this.maritalStatus = normalizeCodedValue(profile.maritalStatus());
        this.fatherName = Strings.normalizeOptional(profile.fatherName());
        this.aadharNumber = normalizeAadharNumber(profile.aadharNumber());
        this.city = Strings.normalizeOptional(profile.addressCity());
        this.state = Strings.normalizeOptional(profile.addressState());
        this.addressLine1 = Strings.normalizeOptional(profile.addressLine1());
        this.addressLine2 = Strings.normalizeOptional(profile.addressLine2());
        this.addressZipCode = Strings.normalizeOptional(profile.addressZipcode());
        this.spouseName = Strings.normalizeOptional(profile.spouseName());
        this.employmentType = normalizeEmploymentType(profile.employmentStatus());
        this.organizationName = Strings.normalizeOptional(profile.organizationName());
        this.employeeId = Strings.normalizeOptional(profile.empId());
        this.employmentCity = Strings.normalizeOptional(profile.employmentCity());
        this.employmentState = Strings.normalizeOptional(profile.employmentState());
        this.employmentZip = Strings.normalizeOptional(profile.employmentZip());
        this.monthlyIncome = normalizeMonthlyIncome(profile.monthlyIncome());
        this.annualIncome = normalizeMonthlyIncome(profile.annualIncome());
        this.bankAccountNumber = Strings.normalizeOptional(profile.bankAccountNumber());
        this.bankName = Strings.normalizeOptional(profile.bankName());
        this.ifscCode = normalizeCodedValue(profile.ifscCode());
        this.accountHolderName = Strings.normalizeOptional(profile.accountHolderName());
        this.referencePersonName = Strings.normalizeOptional(profile.referencePersonName());
        this.referencePersonNumber = Strings.normalizeOptional(profile.referencePersonNumber());
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private static String normalizeEmploymentType(String employmentType) {
        String normalized = Strings.normalizeOptional(employmentType);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private static String normalizeCodedValue(String value) {
        String normalized = Strings.normalizeOptional(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private static String normalizeAadharNumber(String aadharNumber) {
        String normalized = Strings.normalizeOptional(aadharNumber);
        return normalized == null ? null : normalized.replace(" ", "");
    }

    private static BigDecimal normalizeMonthlyIncome(BigDecimal monthlyIncome) {
        if (monthlyIncome == null) {
            return null;
        }

        return monthlyIncome.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
