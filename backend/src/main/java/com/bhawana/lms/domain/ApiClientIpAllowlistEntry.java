package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "api_client_ip_allowlist",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_api_client_ip_allowlist_client_cidr",
                columnNames = {"api_client_id", "cidr"}
        )
)
public class ApiClientIpAllowlistEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_client_id", nullable = false)
    private ApiClient apiClient;

    @Column(nullable = false, length = 64)
    private String cidr;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApiClientIpAllowlistEntry() {
    }

    public ApiClientIpAllowlistEntry(ApiClient apiClient, String cidr) {
        this.id = UUID.randomUUID();
        this.apiClient = apiClient;
        this.cidr = cidr;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    public String getCidr() {
        return cidr;
    }
}
