package com.bhawana.lms.service;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RevocationSource;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthSessionRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.AuthPrincipalCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionRevocationService {

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthSessionRepository authSessionRepository;
    private final AuthAuditService authAuditService;
    private final AuthPrincipalCache authPrincipalCache;

    @PersistenceContext
    private EntityManager entityManager;

    public SessionRevocationService(
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuthSessionRepository authSessionRepository,
            AuthAuditService authAuditService,
            AuthPrincipalCache authPrincipalCache
    ) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authSessionRepository = authSessionRepository;
        this.authAuditService = authAuditService;
        this.authPrincipalCache = authPrincipalCache;
    }

    /**
     * All-user fence by id: principal row lock first (base row only, no nullable
     * outer-join FOR UPDATE), then a refresh to the latest committed state, then
     * session/family rows, then token rows, then audit, then cache evict. The lock query
     * alone does not replace an already-managed stale entity, hence the explicit
     * refresh.
     *
     * <p>Callers holding pending unflushed edits to the same user row must
     * {@code saveAndFlush} them before calling: the refresh re-reads committed state
     * and would discard unflushed caller mutations. Callers must pass only the id and
     * never a pre-loaded entity whose versions they trust.
     */
    @Transactional
    public RevocationResult revokeAllSessionsById(
            UUID userId,
            String actorUsername,
            String reasonOrNull,
            String actorIp,
            String correlationId,
            RevocationSource source
    ) {
        AppUser locked = appUserRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Managed user missing for revocation."));
        entityManager.refresh(locked);
        locked.getRoles().size();
        if (locked.getLsp() != null) {
            locked.getLsp().getCode();
        }
        long previousTokenVersion = locked.getTokenVersion();
        locked.revokeAllSessions();
        AppUser saved = appUserRepository.save(locked);
        // Family rows second, token rows third (stable order: bulk family revoke, then
        // bulk token revoke). Both bulk ops flush before clearing (see repository
        // clearAutomatically) so the tv++ above is persisted before the clear detaches it.
        authSessionRepository.revokeAllForUser(saved.getId());
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
        authPrincipalCache.evictAppUser(saved.getUsername());
        return new RevocationResult(previousTokenVersion, saved.getTokenVersion(), refreshTokensRevoked);
    }

    /**
     * Compatibility wrapper: ignores the passed entity's state and re-locks by id (see
     * {@link #revokeAllSessionsById}). Prefer the id-based entry for new callers.
     */
    @Transactional
    public RevocationResult revokeAllSessions(
            AppUser user,
            String actorUsername,
            String reasonOrNull,
            String actorIp,
            String correlationId,
            RevocationSource source
    ) {
        return revokeAllSessionsById(
                user.getId(), actorUsername, reasonOrNull, actorIp, correlationId, source);
    }

    /**
     * Brute-force auto-lockout as one fence: principal lock first, refresh to latest
     * committed state, then lock mutation, tv bump, family/token revocation, audit and
     * evict in the same transaction. The under-lock re-check makes concurrent lockout
     * attempts idempotent (second one is a no-op returning current versions).
     */
    @Transactional
    public RevocationResult applyBruteForceLockout(
            UUID userId,
            String actorUsername,
            String reason,
            String actorIp,
            String correlationId
    ) {
        AppUser locked = appUserRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Managed user missing for lockout."));
        entityManager.refresh(locked);
        locked.getRoles().size();
        if (locked.getLsp() != null) {
            locked.getLsp().getCode();
        }
        if (locked.isLocked()) {
            return new RevocationResult(locked.getTokenVersion(), locked.getTokenVersion(), 0);
        }
        locked.lockForBruteForce(Instant.now());
        long previousTokenVersion = locked.getTokenVersion();
        locked.revokeAllSessions();
        AppUser saved = appUserRepository.save(locked);
        authSessionRepository.revokeAllForUser(saved.getId());
        int refreshTokensRevoked = refreshTokenRepository.revokeAllForUser(saved.getId());
        authAuditService.recordSessionsRevoked(
                saved,
                actorUsername,
                reason,
                actorIp,
                correlationId,
                RevocationSource.BRUTE_FORCE_LOCKOUT,
                previousTokenVersion,
                saved.getTokenVersion(),
                refreshTokensRevoked
        );
        authPrincipalCache.evictAppUser(saved.getUsername());
        return new RevocationResult(previousTokenVersion, saved.getTokenVersion(), refreshTokensRevoked);
    }

    public record RevocationResult(long previousTokenVersion, long newTokenVersion, int refreshTokensRevoked) {
    }
}
