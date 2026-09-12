package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AuthSession;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Session-family store. Lock ordering is principal ({@code app_user}) first, then
 * family rows; callers must already hold the principal row lock before invoking the
 * {@code ForUpdate} methods here.
 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);

    List<AuthSession> findByUser_IdOrderByCreatedAtAsc(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.user.id = :userId order by s.createdAt asc")
    List<AuthSession> findByUserIdForUpdate(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthSession session
            SET session.revoked = true,
                session.revokedAt = CURRENT_TIMESTAMP
            WHERE session.user.id = :userId
              AND session.revoked = false
            """)
    int revokeAllForUser(@Param("userId") UUID userId);
}
