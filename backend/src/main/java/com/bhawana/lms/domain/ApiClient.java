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
import java.time.Duration;
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

    @Column(name = "previous_secret_hash")
    private String previousSecretHash;

    @Column(name = "previous_secret_valid_until")
    private Instant previousSecretValidUntil;

    @Column(name = "last_rotated_at")
    private Instant lastRotatedAt;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "failed_auth_attempts", nullable = false)
    private int failedAuthAttempts;

    @Column(name = "auth_locked_until")
    private Instant authLockedUntil;

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
        this.tokenVersion = 0L;
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

    public long getTokenVersion() {
        return tokenVersion;
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

    public Instant getLastRotatedAt() {
        return lastRotatedAt;
    }

    public String getPreviousSecretHash() {
        return previousSecretHash;
    }

    public Instant getPreviousSecretValidUntil() {
        return previousSecretValidUntil;
    }

    public void updateManagedProfile(String name, String description, ApiClientStatus status) {
        if (name != null) {
            this.name = name.trim();
        }
        if (description != null) {
            this.description = description;
        }
        if (status != null) {
            this.status = status;
        }
    }

    public void deactivate() {
        this.status = ApiClientStatus.INACTIVE;
    }

    public void revokeAllSessions() {
        this.tokenVersion++;
    }

    public void rotateSecret(String newSecretHash, String previousSecretHash, Instant previousSecretValidUntil) {
        this.previousSecretHash = previousSecretHash;
        this.previousSecretValidUntil = previousSecretValidUntil;
        this.secretHash = newSecretHash;
        this.lastRotatedAt = Instant.now();
        revokeAllSessions();
    }

    public void clearExpiredPreviousSecret(Instant now) {
        if (previousSecretValidUntil != null && !now.isBefore(previousSecretValidUntil)) {
            this.previousSecretHash = null;
            this.previousSecretValidUntil = null;
        }
    }

    public int getFailedAuthAttempts() {
        return failedAuthAttempts;
    }

    public Instant getAuthLockedUntil() {
        return authLockedUntil;
    }

    /** True while a brute-force throttle window is active and has not yet elapsed. */
    public boolean isAuthThrottled(Instant now) {
        return authLockedUntil != null && now.isBefore(authLockedUntil);
    }

    /** Clears the failed-attempt counter and any active throttle after a successful credential check. */
    public void registerSuccessfulAuth() {
        this.failedAuthAttempts = 0;
        this.authLockedUntil = null;
    }

    /**
     * Records a failed credential check. Once {@code maxAttempts} consecutive failures accrue, the
     * client is throttled for {@code lockDuration}. An already-elapsed throttle window resets the
     * count first, so every lockout requires a fresh run of failures rather than accumulating
     * indefinitely across expired windows.
     */
    public void registerFailedAuth(Instant now, int maxAttempts, Duration lockDuration) {
        if (authLockedUntil != null && !now.isBefore(authLockedUntil)) {
            this.failedAuthAttempts = 0;
            this.authLockedUntil = null;
        }
        this.failedAuthAttempts++;
        if (this.failedAuthAttempts >= maxAttempts) {
            this.authLockedUntil = now.plus(lockDuration);
            this.failedAuthAttempts = 0;
        }
    }
}
