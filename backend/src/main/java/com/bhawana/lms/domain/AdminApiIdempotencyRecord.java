package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "admin_api_idempotency_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_admin_api_idempotency_scope",
                columnNames = {"operation_key", "idempotency_key"}
        )
)
public class AdminApiIdempotencyRecord {

    @Id
    private UUID id;

    @Column(name = "operation_key", nullable = false, length = 64)
    private String operationKey;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 128)
    private String requestFingerprint;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    protected AdminApiIdempotencyRecord() {
    }

    public AdminApiIdempotencyRecord(
            String operationKey,
            String idempotencyKey,
            String requestFingerprint,
            int responseStatus,
            String responseBody
    ) {
        this.id = UUID.randomUUID();
        this.operationKey = operationKey;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
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

    public String getOperationKey() {
        return operationKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void completeResponse(int responseStatus, String responseBody) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public int getAttempt() {
        return attempt;
    }

    public void stampLease(String leaseOwner, Instant leaseExpiresAt) {
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
    }
}
