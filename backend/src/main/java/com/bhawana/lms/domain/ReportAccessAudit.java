package com.bhawana.lms.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "report_access_audit")
public class ReportAccessAudit {

    @Id
    private UUID id;

    @Column(name = "actor_username", nullable = false)
    private String actorUsername;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private ReportAccessAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 64)
    private ReportAccessAuditReportType reportType;

    @Column(name = "filter_payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode filterPayload;

    @Column(name = "byte_count", nullable = false)
    private long byteCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_request_id")
    private ReportRequest reportRequest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReportAccessAudit() {
    }

    public ReportAccessAudit(
            String actorUsername,
            String actorIp,
            String correlationId,
            ReportAccessAuditAction action,
            ReportAccessAuditReportType reportType,
            String filterPayloadJson,
            long byteCount,
            ReportRequest reportRequest
    ) {
        this.id = UUID.randomUUID();
        this.actorUsername = actorUsername;
        this.actorIp = actorIp;
        this.correlationId = correlationId;
        this.action = action;
        this.reportType = reportType;
        this.filterPayload = JsonPayloads.requiredObject(filterPayloadJson, "filterPayload");
        this.byteCount = byteCount;
        this.reportRequest = reportRequest;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getActorIp() {
        return actorIp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public ReportAccessAuditAction getAction() {
        return action;
    }

    public ReportAccessAuditReportType getReportType() {
        return reportType;
    }

    public JsonNode getFilterPayload() {
        return filterPayload;
    }

    public long getByteCount() {
        return byteCount;
    }

    public ReportRequest getReportRequest() {
        return reportRequest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
