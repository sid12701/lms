package com.bhawana.lms.repo;

import com.bhawana.lms.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    int deleteByExpiresAtBefore(Instant cutoff);
}
