package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    public static final String AUTH_TYPE_PASSWORD = "PASSWORD";
    public static final String AUTH_TYPE_API_CLIENT = "API_CLIENT";

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_client_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ApiClient apiClient;

    @Column(name = "auth_type", nullable = false, length = 32)
    private String authType;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(String tokenHash, AppUser appUser, Instant expiresAt) {
        if (appUser == null) {
            throw new IllegalArgumentException("appUser is required for a PASSWORD refresh token.");
        }
        this.id = UUID.randomUUID();
        this.tokenHash = tokenHash;
        this.appUser = appUser;
        this.authType = AUTH_TYPE_PASSWORD;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    public RefreshToken(String tokenHash, ApiClient apiClient, Instant expiresAt) {
        if (apiClient == null) {
            throw new IllegalArgumentException("apiClient is required for an API_CLIENT refresh token.");
        }
        this.id = UUID.randomUUID();
        this.tokenHash = tokenHash;
        this.apiClient = apiClient;
        this.authType = AUTH_TYPE_API_CLIENT;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void revoke() {
        this.revoked = true;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    public String getAuthType() {
        return authType;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
