package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "lsp")
public class Lsp {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LspStatus status;

    @Column(name = "webhook_enabled", nullable = false)
    private boolean webhookEnabled;

    @Column(name = "webhook_endpoint_url", length = 500)
    private String webhookEndpointUrl;

    @Column(name = "webhook_signing_secret", length = 255)
    private String webhookSigningSecret;

    @Column(name = "webhook_event_types", length = 500)
    private String webhookEventTypes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "enforce_ui_allowlist", nullable = false)
    private boolean enforceUiAllowlist;

    @Column(name = "enforce_api_allowlist", nullable = false)
    private boolean enforceApiAllowlist;

    protected Lsp() {
    }

    public Lsp(String code, String name, LspStatus status) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.status = status;
        this.webhookEnabled = false;
        this.webhookEventTypes = "";
        this.tokenVersion = 0L;
        this.enforceUiAllowlist = false;
        this.enforceApiAllowlist = false;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public LspStatus getStatus() {
        return status;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public void updateStatus(LspStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("LSP status is required.");
        }
        this.status = status;
    }

    public void revokeAllSessions() {
        this.tokenVersion++;
    }

    public boolean isEnforceUiAllowlist() {
        return enforceUiAllowlist;
    }

    public boolean isEnforceApiAllowlist() {
        return enforceApiAllowlist;
    }

    public void updateAllowlistEnforcement(boolean enforceUiAllowlist, boolean enforceApiAllowlist) {
        this.enforceUiAllowlist = enforceUiAllowlist;
        this.enforceApiAllowlist = enforceApiAllowlist;
    }

    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    public String getWebhookEndpointUrl() {
        return webhookEndpointUrl;
    }

    public String getWebhookSigningSecret() {
        return webhookSigningSecret;
    }

    public List<WebhookEventType> getWebhookEventTypes() {
        if (webhookEventTypes == null || webhookEventTypes.isBlank()) {
            return List.of();
        }

        return Arrays.stream(webhookEventTypes.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(WebhookEventType::valueOf)
                .toList();
    }

    public void updateWebhookSubscription(
            boolean enabled,
            String endpointUrl,
            String signingSecret,
            List<WebhookEventType> eventTypes
    ) {
        this.webhookEnabled = enabled;
        this.webhookEndpointUrl = endpointUrl;
        this.webhookSigningSecret = signingSecret;
        this.webhookEventTypes = eventTypes == null || eventTypes.isEmpty()
                ? ""
                : eventTypes.stream().map(Enum::name).sorted().reduce((left, right) -> left + "," + right).orElse("");
    }
}
