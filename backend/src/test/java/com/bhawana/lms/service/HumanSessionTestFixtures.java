package com.bhawana.lms.service;

import com.bhawana.lms.common.api.TokenResponse;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthSession;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthSessionRepository;
import com.bhawana.lms.security.SessionPolicyEpochProvider;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only fixture helper (test sources). Mints human access tokens with a REAL
 * session family through the production typed-spec minter — the same package-private
 * entry the fenced coordinator uses — so audience/expiry/status negatives prove their
 * own contract rather than failing for sid absence. There is no production backdoor
 * minter; this helper lives outside the main sourceset.
 */
@Component
public class HumanSessionTestFixtures {

    private final AppUserRepository appUserRepository;
    private final AuthSessionRepository authSessionRepository;
    private final AuthTokenService authTokenService;
    private final SessionPolicyEpochProvider sessionPolicyEpochProvider;

    public HumanSessionTestFixtures(
            AppUserRepository appUserRepository,
            AuthSessionRepository authSessionRepository,
            AuthTokenService authTokenService,
            SessionPolicyEpochProvider sessionPolicyEpochProvider
    ) {
        this.appUserRepository = appUserRepository;
        this.authSessionRepository = authSessionRepository;
        this.authTokenService = authTokenService;
        this.sessionPolicyEpochProvider = sessionPolicyEpochProvider;
    }

    /**
     * Mints a human access token bound to a freshly created live family for the given
     * username. Runs in the caller's transaction scope (tests wrap with admin scope).
     */
    @Transactional
    public TokenResponse mintHumanTokenForUsername(String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Unknown fixture user: " + username));
        AuthSession session = authSessionRepository.save(
                new AuthSession(user, sessionPolicyEpochProvider.currentEpoch()));
        return authTokenService.mintHumanFamilyToken(fixtureSpec(user, session.getId()));
    }

    /**
     * Returns the live family id backing a freshly minted token for the user.
     */
    @Transactional
    public UUID liveFamilyIdForUsername(String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Unknown fixture user: " + username));
        AuthSession session = authSessionRepository.save(
                new AuthSession(user, sessionPolicyEpochProvider.currentEpoch()));
        authTokenService.mintHumanFamilyToken(fixtureSpec(user, session.getId()));
        return session.getId();
    }

    private static AuthTokenService.HumanFamilyTokenSpec fixtureSpec(AppUser user, UUID familyId) {
        com.bhawana.lms.domain.Lsp lsp = user.getLsp();
        return new AuthTokenService.HumanFamilyTokenSpec(
                user.getUsername(),
                user.getRoles().stream().map(role -> role.getCode().name()).toList(),
                user.isPasswordChangeRequired(),
                user.getPasswordChangedAt().toEpochMilli(),
                user.getTokenVersion(),
                lsp == null ? null : lsp.getId().toString(),
                lsp == null ? null : lsp.getCode(),
                lsp == null ? null : lsp.getName(),
                familyId);
    }

    public static <T> T asAdmin(java.util.function.Supplier<T> task) {
        return TenantScopedExecution.callAsAdmin(task);
    }
}
