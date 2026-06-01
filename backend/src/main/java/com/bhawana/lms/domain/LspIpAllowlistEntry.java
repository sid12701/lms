package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "lsp_api_ip_allowlist",
        uniqueConstraints = @UniqueConstraint(name = "uk_lsp_api_ip_allowlist_lsp_cidr", columnNames = {"lsp_id", "cidr"})
)
public class LspIpAllowlistEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @Column(nullable = false, length = 64)
    private String cidr;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LspIpAllowlistEntry() {
    }

    public LspIpAllowlistEntry(Lsp lsp, String cidr, String description) {
        this.id = UUID.randomUUID();
        this.lsp = lsp;
        this.cidr = cidr;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public String getCidr() {
        return cidr;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
