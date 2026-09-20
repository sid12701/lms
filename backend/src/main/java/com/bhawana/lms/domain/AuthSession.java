package com.bhawana.lms.domain;

import com.bhawana.lms.common.util.PersistedTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One row per human login session (family). The access-JWT {@code sid} claim binds
 * to this id; revoking the family kills only its own refresh lineage, never user-wide
 * state. Machine (API_CLIENT) sessions never get a family row.
 */
@Entity
@Table(name = "auth_session")
public class AuthSession {

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    @Column(name = "policy_epoch", nullable = false, length = 128)
    private String policyEpoch;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthSession() {
    }

    public AuthSession(AppUser user, String policyEpoch) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.policyEpoch = policyEpoch;
        this.revoked = false;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = PersistedTimestamp.now();
        }
    }

    public void revoke(Instant revokedAt) {
        this.revoked = true;
        this.revokedAt = PersistedTimestamp.normalize(revokedAt);
    }

    public UUID getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public UUID getUserId() {
        return user != null ? user.getId() : null;
    }

    public String getPolicyEpoch() {
        return policyEpoch;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
