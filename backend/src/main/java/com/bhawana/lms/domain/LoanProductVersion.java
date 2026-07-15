package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_product_version")
public class LoanProductVersion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_product_id", nullable = false)
    private LoanProduct loanProduct;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

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

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanProductVersion() {
    }

    public LoanProductVersion(
            LoanProduct loanProduct,
            int versionNumber,
            BigDecimal minPrincipal,
            BigDecimal maxPrincipal,
            BigDecimal interestRate,
            BigDecimal processingFeeRate,
            int minTenureMonths,
            int maxTenureMonths,
            Instant effectiveFrom,
            String createdBy
    ) {
        this.id = UUID.randomUUID();
        this.loanProduct = loanProduct;
        this.versionNumber = versionNumber;
        this.minPrincipal = minPrincipal;
        this.maxPrincipal = maxPrincipal;
        this.interestRate = interestRate;
        this.processingFeeRate = processingFeeRate;
        this.minTenureMonths = minTenureMonths;
        this.maxTenureMonths = maxTenureMonths;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public LoanProduct getLoanProduct() {
        return loanProduct;
    }

    public int getVersionNumber() {
        return versionNumber;
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

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
