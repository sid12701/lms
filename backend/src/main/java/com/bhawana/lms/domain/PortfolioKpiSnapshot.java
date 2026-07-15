package com.bhawana.lms.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "portfolio_kpi_snapshot")
public class PortfolioKpiSnapshot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lsp_id")
    private Lsp lsp;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "total_disbursed", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDisbursed;

    @Column(name = "total_outstanding", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalOutstanding;

    @Column(name = "total_overdue", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalOverdue;

    @Column(name = "status_counts", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode statusCounts;

    @Column(name = "dpd_buckets", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode dpdBuckets;

    @Column(name = "avg_approval_tat_hours", precision = 10, scale = 2)
    private BigDecimal avgApprovalTatHours;

    protected PortfolioKpiSnapshot() {
    }

    public PortfolioKpiSnapshot(
            Lsp lsp,
            Instant computedAt,
            BigDecimal totalDisbursed,
            BigDecimal totalOutstanding,
            BigDecimal totalOverdue,
            JsonNode statusCounts,
            JsonNode dpdBuckets,
            BigDecimal avgApprovalTatHours
    ) {
        this.id = UUID.randomUUID();
        this.lsp = lsp;
        this.computedAt = computedAt;
        this.totalDisbursed = totalDisbursed;
        this.totalOutstanding = totalOutstanding;
        this.totalOverdue = totalOverdue;
        this.statusCounts = statusCounts;
        this.dpdBuckets = dpdBuckets;
        this.avgApprovalTatHours = avgApprovalTatHours;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public UUID getLspId() {
        return lsp == null ? null : lsp.getId();
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public BigDecimal getTotalDisbursed() {
        return totalDisbursed;
    }

    public BigDecimal getTotalOutstanding() {
        return totalOutstanding;
    }

    public BigDecimal getTotalOverdue() {
        return totalOverdue;
    }

    public JsonNode getStatusCounts() {
        return statusCounts;
    }

    public JsonNode getDpdBuckets() {
        return dpdBuckets;
    }

    public BigDecimal getAvgApprovalTatHours() {
        return avgApprovalTatHours;
    }
}
