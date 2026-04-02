package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_client")
public class ApiClient {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, unique = true, length = 128)
    private String clientId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "secret_hash", nullable = false)
    private String secretHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApiClientStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiClient() {
    }

    public ApiClient(
            String clientId,
            Lsp lsp,
            String name,
            String description,
            String secretHash,
            ApiClientStatus status
    ) {
        this.id = UUID.randomUUID();
        this.clientId = clientId;
        this.lsp = lsp;
        this.name = name;
        this.description = description;
        this.secretHash = secretHash;
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

    public String getClientId() {
        return clientId;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ApiClientStatus getStatus() {
        return status;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void markUsed() {
        this.lastUsedAt = Instant.now();
    }
}
