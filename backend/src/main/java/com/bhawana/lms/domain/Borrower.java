package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "borrower")
public class Borrower {

    @Id
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(nullable = false, unique = true, length = 10)
    private String pan;

    @Column(nullable = false, length = 32)
    private String mobile;

    @Column(length = 255)
    private String email;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 128)
    private String city;

    @Column(length = 128)
    private String state;

    @Column(name = "employment_type", length = 64)
    private String employmentType;

    @Column(name = "monthly_income", precision = 19, scale = 2)
    private BigDecimal monthlyIncome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Borrower() {
    }

    public Borrower(String fullName, String pan, String mobile, String email) {
        this(fullName, pan, mobile, email, null, null, null, null, null);
    }

    public Borrower(
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
        this.id = UUID.randomUUID();
        this.fullName = fullName.trim();
        this.pan = pan.trim().toUpperCase();
        this.mobile = mobile.trim();
        this.email = normalizeEmail(email);
        this.dateOfBirth = dateOfBirth;
        this.city = normalizeOptional(city);
        this.state = normalizeOptional(state);
        this.employmentType = normalizeEmploymentType(employmentType);
        this.monthlyIncome = normalizeMonthlyIncome(monthlyIncome);
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

    public String getEmploymentType() {
        return employmentType;
    }

    public BigDecimal getMonthlyIncome() {
        return monthlyIncome;
    }

    public void refreshProfile(
            String fullName,
            String mobile,
            String email
    ) {
        refreshProfile(fullName, mobile, email, null, null, null, null, null);
    }

    public void refreshProfile(
            String fullName,
            String mobile,
            String email,
            LocalDate dateOfBirth,
            String city,
            String state,
            String employmentType,
            BigDecimal monthlyIncome
    ) {
        this.fullName = fullName.trim();
        this.mobile = mobile.trim();
        this.email = normalizeEmail(email);
        this.dateOfBirth = dateOfBirth;
        this.city = normalizeOptional(city);
        this.state = normalizeOptional(state);
        this.employmentType = normalizeEmploymentType(employmentType);
        this.monthlyIncome = normalizeMonthlyIncome(monthlyIncome);
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

    private static BigDecimal normalizeMonthlyIncome(BigDecimal monthlyIncome) {
        if (monthlyIncome == null) {
            return null;
        }

        return monthlyIncome.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
