package com.bhawana.lms.domain;

import com.bhawana.lms.common.util.PersistedTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "lsp_id")
    private Lsp lsp;

    @Column(nullable = false, unique = true, length = 128)
    private String username;

    @Column(unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<AppRole> roles = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "lock_reason", length = 64)
    private String lockReason;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "failed_login_window_started_at")
    private Instant failedLoginWindowStartedAt;

    public static final String LOCK_REASON_BRUTE_FORCE = "BRUTE_FORCE";

    protected AppUser() {
    }

    public AppUser(String username, String email, String passwordHash, UserStatus status, Lsp lsp, Set<AppRole> roles) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = false;
        this.passwordChangedAt = PersistedTimestamp.now();
        this.status = status;
        this.lsp = lsp;
        this.roles = new LinkedHashSet<>(roles);
        this.tokenVersion = 0L;
    }

    @PrePersist
    void onCreate() {
        Instant now = PersistedTimestamp.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = PersistedTimestamp.now();
    }

    public UUID getId() {
        return id;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Set<AppRole> getRoles() {
        return roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public void updatePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void requirePasswordChange(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = true;
        this.passwordChangedAt = PersistedTimestamp.now();
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = false;
        this.passwordChangedAt = PersistedTimestamp.now();
    }

    public void updateManagedProfile(
            String email,
            Lsp lsp,
            Set<AppRole> roles
    ) {
        this.email = email;
        this.lsp = lsp;
        this.roles = new LinkedHashSet<>(roles);
    }

    public void changeStatus(UserStatus newStatus) {
        if (this.status == newStatus) {
            return;
        }
        this.status = newStatus;
        this.tokenVersion++;
    }

    public void revokeAllSessions() {
        this.tokenVersion++;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public String getLockReason() {
        return lockReason;
    }

    public boolean isLocked() {
        return lockedAt != null;
    }

    public void lockForBruteForce(Instant lockedAt) {
        this.lockedAt = PersistedTimestamp.normalize(lockedAt);
        this.lockReason = LOCK_REASON_BRUTE_FORCE;
    }

    public void unlockForReset() {
        this.lockedAt = null;
        this.lockReason = null;
        clearFailedLoginWindow();
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getFailedLoginWindowStartedAt() {
        return failedLoginWindowStartedAt;
    }

    /** Clears the rolling failed-login counter after a successful credential check. */
    public void clearFailedLoginWindow() {
        this.failedLoginAttempts = 0;
        this.failedLoginWindowStartedAt = null;
    }

    /**
     * Records a failed login against an existing user. Returns {@code true} when the live count
     * reaches {@code threshold} inside the current window (caller applies brute-force lockout).
     * An elapsed window resets the count before this failure is applied.
     */
    public boolean registerFailedLogin(Instant now, int threshold, Duration windowDuration) {
        if (isLocked()) {
            return false;
        }
        if (failedLoginWindowStartedAt != null
                && now.isAfter(failedLoginWindowStartedAt.plus(windowDuration))) {
            clearFailedLoginWindow();
        }
        if (failedLoginAttempts == 0) {
            failedLoginWindowStartedAt = PersistedTimestamp.normalize(now);
        }
        failedLoginAttempts++;
        return failedLoginAttempts >= threshold;
    }
}
