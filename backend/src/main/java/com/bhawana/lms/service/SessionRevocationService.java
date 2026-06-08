package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RevocationSource;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionRevocationService {

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthAuditService authAuditService;

    public SessionRevocationService(
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuthAuditService authAuditService
    ) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authAuditService = authAuditService;
    }

    @Transactional
    public RevocationResult revokeAllSessions(
            AppUser user,
            String actorUsername,
            String reasonOrNull,
            String actorIp,
            String correlationId,
            RevocationSource source
    ) {
        long previousTokenVersion = user.getTokenVersion();
        user.revokeAllSessions();
        AppUser saved = appUserRepository.save(user);
        int refreshTokensRevoked = refreshTokenRepository.revokeAllForUser(saved.getId());
        authAuditService.recordSessionsRevoked(
                saved,
                actorUsername,
                reasonOrNull,
                actorIp,
                correlationId,
                source,
                previousTokenVersion,
                saved.getTokenVersion(),
                refreshTokensRevoked
        );
        return new RevocationResult(previousTokenVersion, saved.getTokenVersion(), refreshTokensRevoked);
    }

    public record RevocationResult(long previousTokenVersion, long newTokenVersion, int refreshTokensRevoked) {
    }
}
