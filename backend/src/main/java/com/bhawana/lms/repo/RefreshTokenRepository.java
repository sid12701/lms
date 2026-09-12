package com.bhawana.lms.repo;

import com.bhawana.lms.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Token-row lock. Callers must already hold the principal ({@code app_user} or
     * {@code api_client}) row lock before invoking this, preserving principal-first order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token where token.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    java.util.List<RefreshToken> findByFamily_IdOrderByCreatedAtAsc(UUID familyId);

    /**
     * Authoritative single-current-head lookup. The V126 partial unique index
     * guarantees at most one unrevoked row per family, so the benign-parent check needs
     * only this bounded row — never a lock over the whole lineage history.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token from RefreshToken token
            where token.family.id = :familyId
              and token.revoked = false
            """)
    Optional<RefreshToken> findLiveHeadByFamilyIdForUpdate(@Param("familyId") UUID familyId);

    int deleteByExpiresAtBefore(Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken token
            SET token.revoked = true
            WHERE token.appUser.id = :userId
              AND token.revoked = false
            """)
    int revokeAllForUser(@Param("userId") UUID userId);

    /**
     * Per-family revoke revokes only live rows of that session family (no tv bump,
     * no cross-family effect). Unknown hashes never reach here: callers resolve the
     * family from a real row first, so an unknown hash revokes nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken token
            SET token.revoked = true,
                token.revokedAt = CURRENT_TIMESTAMP
            WHERE token.family.id = :familyId
              AND token.revoked = false
            """)
    int revokeLiveFamilyRows(@Param("familyId") UUID familyId);
}
