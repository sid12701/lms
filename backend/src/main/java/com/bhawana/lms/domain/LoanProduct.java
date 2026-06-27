package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_product")
public class LoanProduct {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "min_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal minPrincipal;

    @Column(name = "max_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxPrincipal;

    @Column(name = "interest_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Column(name = "processing_fee_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal processingFeeRate;

    @Column(name = "min_tenure_months", nullable = false)
    private int minTenureMonths;

    @Column(name = "max_tenure_months", nullable = false)
    private int maxTenureMonths;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LoanProductStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanProduct() {
    }

    public LoanProduct(
            String code,
            String name,
            BigDecimal minPrincipal,
            BigDecimal maxPrincipal,
            BigDecimal interestRate,
            BigDecimal processingFeeRate,
            int minTenureMonths,
            int maxTenureMonths,
            LoanProductStatus status
    ) {
        this.id = UUID.randomUUID();
        this.code = code.trim().toUpperCase();
        this.name = name.trim();
        this.minPrincipal = minPrincipal;
        this.maxPrincipal = maxPrincipal;
        this.interestRate = interestRate;
        this.processingFeeRate = processingFeeRate;
        this.minTenureMonths = minTenureMonths;
        this.maxTenureMonths = maxTenureMonths;
        this.status = status;
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

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getMinPrincipal() {
        return minPrincipal;
    }

    public BigDecimal getMaxPrincipal() {
        return maxPrincipal;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public BigDecimal getProcessingFeeRate() {
        return processingFeeRate;
    }

    public int getMinTenureMonths() {
        return minTenureMonths;
    }

    public int getMaxTenureMonths() {
        return maxTenureMonths;
    }

    public LoanProductStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void update(
            String code,
            String name,
            BigDecimal minPrincipal,
            BigDecimal maxPrincipal,
            BigDecimal interestRate,
            BigDecimal processingFeeRate,
            int minTenureMonths,
            int maxTenureMonths,
            LoanProductStatus status
    ) {
        this.code = code.trim().toUpperCase();
        this.name = name.trim();
        this.minPrincipal = minPrincipal;
        this.maxPrincipal = maxPrincipal;
        this.interestRate = interestRate;
        this.processingFeeRate = processingFeeRate;
        this.minTenureMonths = minTenureMonths;
        this.maxTenureMonths = maxTenureMonths;
        this.status = status;
    }
}
