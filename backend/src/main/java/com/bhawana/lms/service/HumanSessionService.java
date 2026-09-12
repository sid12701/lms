package com.bhawana.lms.service;

import com.bhawana.lms.common.api.TokenResponse;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.api.error.LspSurfaceIpAccessDeniedException;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.AuthSession;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.domain.RevocationSource;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthSessionRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.AuthPrincipalCache;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.security.SessionPolicyEpochProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fenced session coordinator for BOTH human and machine refresh lifecycles (single
 * shared fence discipline; the machine branch keeps its no-family/no-sid/no-epoch shape
 * and never touches human families).
 *
 * <p>Transaction ownership is explicit: every public entry requires NO surrounding
 * caller transaction (enforced, tested) and owns exactly one fencing transaction with
 * REQUIRED propagation. Because no caller TX ever exists, each fence runs on a fresh
 * persistence context — no global {@code clear()} that could discard unrelated pending
 * caller writes, and no stale managed entities to refresh.
 *
 * <p>Lock order inside every fence is principal ({@code app_user} / {@code api_client}
 * base row, never a nullable outer-join {@code FOR UPDATE}) first, then
 * {@code auth_session} family row (human only), then {@code refresh_token} rows, then
 * audit, then cache evict. Routing probes (owner id, family id) are detached scalars
 * read outside the fence; inside, everything is re-locked principal → family → token
 * and ownership is re-verified after the locks. A {@code FOR UPDATE} row lock alone is
 * never treated as fresh state.
 *
 * <p>The single fencing transaction commits all state changes and returns a sealed
 * classification; nothing throws after a revocation inside it (that would roll the revoke
 * back). Success audits join the fencing transaction (audit failure rolls the mutation
 * back); failure audits for paths with no state change are written by the caller outside.
 */
@Service
public class HumanSessionService {

    /** Benign direct-parent loser tolerance: race tolerance, not a replay window. */
    public static final Duration LOSER_TOLERANCE = Duration.ofSeconds(60);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository appUserRepository;
    private final ApiClientRepository apiClientRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthSessionRepository authSessionRepository;
    private final AuthTokenService authTokenService;
    private final AuthAuditService authAuditService;
    private final AuthPrincipalCache authPrincipalCache;
    private final SecurityProperties securityProperties;
    private final SessionPolicyEpochProvider sessionPolicyEpochProvider;
    private final PasswordEncoder passwordEncoder;
    private final ApiClientAuthenticationService apiClientAuthenticationService;
    private final LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService;
    private final HumanLoginLockoutService humanLoginLockoutService;
    private final TransactionTemplate transactionTemplate;

    /**
     * Test-only seams (never set in production):
     * <ul>
     *   <li>{@code preIssuanceHook} runs between credential authentication and the fenced
     *       issuance transaction (auth→issuance gap tests).</li>
     *   <li>{@code preRefreshLockHook} runs inside the fencing transaction before the
     *       principal lock (overlap/fence-race tests).</li>
     *   <li>{@code prePasswordFenceHook} runs after owner resolution before the password
     *       fencing transaction (deferred-request gap tests).</li>
     * </ul>
     */
    volatile Runnable preIssuanceHook;
    volatile Runnable preRefreshLockHook;
    volatile Runnable prePasswordFenceHook;

    public void setPreIssuanceHookForTest(Runnable hook) {
        this.preIssuanceHook = hook;
    }

    public void setPreRefreshLockHookForTest(Runnable hook) {
        this.preRefreshLockHook = hook;
    }

    public void setPrePasswordFenceHookForTest(Runnable hook) {
        this.prePasswordFenceHook = hook;
    }

    public HumanSessionService(
            AuthenticationManager authenticationManager,
            AppUserRepository appUserRepository,
            ApiClientRepository apiClientRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuthSessionRepository authSessionRepository,
            AuthTokenService authTokenService,
            AuthAuditService authAuditService,
            AuthPrincipalCache authPrincipalCache,
            SecurityProperties securityProperties,
            SessionPolicyEpochProvider sessionPolicyEpochProvider,
            PasswordEncoder passwordEncoder,
            ApiClientAuthenticationService apiClientAuthenticationService,
            LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService,
            HumanLoginLockoutService humanLoginLockoutService,
            PlatformTransactionManager transactionManager
    ) {
        this.authenticationManager = authenticationManager;
        this.appUserRepository = appUserRepository;
        this.apiClientRepository = apiClientRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authSessionRepository = authSessionRepository;
        this.authTokenService = authTokenService;
        this.authAuditService = authAuditService;
        this.authPrincipalCache = authPrincipalCache;
        this.securityProperties = securityProperties;
        this.sessionPolicyEpochProvider = sessionPolicyEpochProvider;
        this.passwordEncoder = passwordEncoder;
        this.apiClientAuthenticationService = apiClientAuthenticationService;
        this.lspSurfaceIpAllowlistService = lspSurfaceIpAllowlistService;
        this.humanLoginLockoutService = humanLoginLockoutService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private static void requireNoCallerTransaction(String operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "HumanSessionService." + operation
                            + " requires no surrounding transaction: fencing transactions own their boundary.");
        }
    }

    // ---- login: authenticate outside, issue access+refresh from one fenced TX ----

    /**
     * Password login returning access plus refresh from one principal-fenced issuance
     * transaction. The version-checked proof (userId + tv + pwdvMillis) is captured from
     * the exact credential snapshot <em>before</em> authentication; the issuance
     * transaction re-locks the principal and additionally re-verifies the presented
     * password against the locked hash, so a password reset finishing anywhere in the
     * auth→issuance gap aborts the mint instead of issuing current-version credentials
     * from a stale proof. In-fence rejections are audited by the caller outside the
     * fence (auditing inside then throwing would roll the audit back).
     */
    public PasswordLoginIssued login(String email, String password, String remoteAddress) {
        requireNoCallerTransaction("login");
        String normalizedEmail = requireField(email, "email").toLowerCase();
        requireField(password, "password");
        String correlationId = CorrelationIdHolder.get();
        // Detached pre-auth proof from the exact snapshot being authenticated. A reset
        // landing after this point changes tv/pwdv/hash, which the fence re-checks.
        AppUser probe = appUserRepository.findByEmail(normalizedEmail).orElse(null);
        String username = resolveLoginUsername(normalizedEmail, probe);
        String auditUsername = probe != null ? probe.getUsername() : username;
        PreAuthProof preProof = probe != null
                ? new PreAuthProof(
                        probe.getId(), probe.getTokenVersion(),
                        probe.getPasswordChangedAt().toEpochMilli())
                : null;
        if (probe != null && probe.isLocked()) {
            authAuditService.recordLoginFailure(
                    auditUsername, AuthEventFailureReason.INVALID_CREDENTIALS, remoteAddress, correlationId);
            throw new BadCredentialsException("Invalid credentials");
        }
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException exception) {
            if (probe != null) {
                humanLoginLockoutService.registerFailedLogin(
                        probe.getId(),
                        auditUsername,
                        authAuditService.resolveAuthenticationFailureReason(exception),
                        remoteAddress,
                        correlationId);
            } else {
                authAuditService.recordLoginFailureFromException(
                        auditUsername, exception, remoteAddress, correlationId);
            }
            throw exception;
        }
        Runnable hook = preIssuanceHook;
        if (hook != null) {
            hook.run();
        }
        // Owner id for the fence, resolved outside it (detached). Never reuse the probe
        // entity itself inside the fence.
        UUID issuanceId = preProof != null
                ? preProof.userId()
                : appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);
        if (issuanceId == null) {
            authAuditService.recordLoginFailure(
                    auditUsername, AuthEventFailureReason.OTHER, remoteAddress, correlationId);
            throw new BadCredentialsException("Invalid credentials");
        }
        final String presentedPassword = password;
        try {
            return transactionTemplate.execute(status -> issueLoginTx(
                    issuanceId, auditUsername, preProof, presentedPassword, remoteAddress, correlationId));
        } catch (LoginFailed failed) {
            if (failed.reason() == AuthEventFailureReason.INVALID_CREDENTIALS) {
                humanLoginLockoutService.registerFailedLogin(
                        issuanceId, failed.username(), failed.reason(), remoteAddress, correlationId);
            } else {
                authAuditService.recordLoginFailure(
                        failed.username(), failed.reason(), remoteAddress, correlationId);
            }
            throw failed.rethrowable();
        }
    }

    private PasswordLoginIssued issueLoginTx(
            UUID issuanceId,
            String auditUsername,
            PreAuthProof preProof,
            String presentedPassword,
            String remoteAddress,
            String correlationId
    ) {
        // Principal lock first (base row only; roles/LSP initialized after the lock).
        AppUser locked = appUserRepository.findByIdForUpdate(issuanceId).orElse(null);
        if (locked == null) {
            throw new LoginFailed(auditUsername, AuthEventFailureReason.OTHER,
                    new BadCredentialsException("Invalid credentials"));
        }
        locked.getRoles().size();
        if (locked.getLsp() != null) {
            locked.getLsp().getStatus();
            locked.getLsp().getId();
        }
        String username = locked.getUsername();
        if (locked.isLocked()) {
            throw new LoginFailed(username, AuthEventFailureReason.INVALID_CREDENTIALS,
                    new BadCredentialsException("Invalid credentials"));
        }
        if (locked.getStatus() != UserStatus.ACTIVE) {
            throw new LoginFailed(username, AuthEventFailureReason.USER_INACTIVE,
                    new BadCredentialsException("Invalid credentials"));
        }
        // Proof binding to the exact authenticated snapshot: tv/pwdv drift (admin reset or
        // all-user revocation in the auth→issuance gap) is SESSION_INVALID_STATUS, not a
        // credential failure for brute-force counting. Wrong password with unchanged
        // versions is INVALID_CREDENTIALS.
        if (preProof != null
                && (locked.getTokenVersion() != preProof.tokenVersion()
                    || locked.getPasswordChangedAt().toEpochMilli() != preProof.passwordChangedAtMillis())) {
            throw new LoginFailed(username, AuthEventFailureReason.SESSION_INVALID_STATUS,
                    new BadCredentialsException("Session is no longer valid; please sign in again."));
        }
        if (!passwordEncoder.matches(presentedPassword, locked.getPasswordHash())) {
            throw new LoginFailed(username, AuthEventFailureReason.INVALID_CREDENTIALS,
                    new BadCredentialsException("Invalid credentials"));
        }
        if (locked.getLsp() != null && locked.getLsp().getStatus() != LspStatus.ACTIVE) {
            throw new LoginFailed(username, AuthEventFailureReason.LSP_INACTIVE,
                    new com.bhawana.lms.security.LspInactiveAuthenticationException());
        }
        if (locked.getLsp() != null && hasLspUiRole(locked)) {
            try {
                lspSurfaceIpAllowlistService.assertUiLoginAllowed(locked.getLsp().getId(), remoteAddress);
            } catch (LspSurfaceIpAccessDeniedException exception) {
                throw new LoginFailed(username, AuthEventFailureReason.OTHER, exception);
            }
        }
        locked.clearFailedLoginWindow();
        String epoch = sessionPolicyEpochProvider.currentEpoch();
        AuthSession session = authSessionRepository.save(new AuthSession(locked, epoch));
        TokenResponse access = authTokenService.mintHumanFamilyToken(familySpec(locked, session.getId()));
        RawRefresh raw = newRawRefreshToken();
        RefreshToken row = new RefreshToken(raw.hash(), locked, raw.expiresAt());
        row.attachFamily(session, locked.getTokenVersion(),
                locked.getPasswordChangedAt().toEpochMilli(), epoch);
        refreshTokenRepository.save(row);
        // Success audit joins the same TX: audit failure rolls issuance back.
        authAuditService.recordLoginSuccess(username, locked, remoteAddress, correlationId);
        authPrincipalCache.evictAppUser(username);
        return new PasswordLoginIssued(access, raw.value(), username, session.getId());
    }

    // ---- client credentials: authenticate outside, mint access from one fenced TX ----

    /**
     * Fenced client-credentials issuance. Credential proof runs outside the fence;
     * the issuance transaction locks the api_client row first, re-verifies the presented
     * secret and live status/version, then mints access only (no refresh row).
     */
    public ClientCredentialsIssued issueClientCredentials(
            String clientId,
            String clientSecret,
            String remoteAddress,
            String correlationId
    ) {
        requireNoCallerTransaction("issueClientCredentials");
        String normalizedClientId = requireField(clientId, "clientId");
        String normalizedSecret = requireField(clientSecret, "clientSecret");
        apiClientAuthenticationService.authenticate(normalizedClientId, normalizedSecret);
        ApiClient probe = apiClientRepository.findByClientId(normalizedClientId)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        MachinePreProof preProof = new MachinePreProof(
                probe.getId(), probe.getTokenVersion(), probe.getStatus());
        Runnable hook = preIssuanceHook;
        if (hook != null) {
            hook.run();
        }
        try {
            return transactionTemplate.execute(status -> issueClientCredentialsTx(
                    preProof, normalizedSecret, remoteAddress, correlationId));
        } catch (ClientCredentialsFailed failed) {
            throw failed.rethrowable();
        }
    }

    private ClientCredentialsIssued issueClientCredentialsTx(
            MachinePreProof preProof,
            String presentedSecret,
            String remoteAddress,
            String correlationId
    ) {
        ApiClient locked = apiClientRepository.findByIdForUpdate(preProof.clientId()).orElse(null);
        if (locked == null) {
            throw new ClientCredentialsFailed(new BadCredentialsException("Invalid credentials"));
        }
        String subject = locked.getClientId();
        if (!apiClientAuthenticationService.verifyClientSecret(locked, presentedSecret)) {
            throw new ClientCredentialsFailed(
                    new BadCredentialsException("Invalid credentials"), subject);
        }
        if (locked.getTokenVersion() != preProof.tokenVersion()) {
            throw new ClientCredentialsFailed(
                    new BadCredentialsException("Invalid credentials"), subject);
        }
        if (locked.getStatus() != preProof.status() || locked.getStatus() != ApiClientStatus.ACTIVE) {
            throw new ClientCredentialsFailed(
                    new BadCredentialsException("Invalid credentials"), subject);
        }
        if (locked.getLsp() == null || locked.getLsp().getStatus() != LspStatus.ACTIVE) {
            throw new ClientCredentialsFailed(
                    new BadCredentialsException("Invalid credentials"), subject);
        }
        try {
            lspSurfaceIpAllowlistService.assertApiTokenIssuanceAllowed(locked.getLsp().getId(), remoteAddress);
        } catch (LspSurfaceIpAccessDeniedException exception) {
            throw new ClientCredentialsFailed(exception, subject);
        }
        if (locked.isAuthThrottled(Instant.now())) {
            throw new ClientCredentialsFailed(new BadCredentialsException("Invalid credentials"), subject);
        }
        locked.registerSuccessfulAuth();
        locked.markUsed();
        TokenResponse access = authTokenService.mintMachineTokenResponse(
                locked.getClientId(),
                locked.getName(),
                locked.getLsp().getId().toString(),
                locked.getLsp().getCode(),
                locked.getLsp().getTokenVersion(),
                locked.getTokenVersion());
        authAuditService.recordApiClientTokenSuccess(locked, remoteAddress, correlationId);
        authPrincipalCache.evictApiClient(subject);
        return new ClientCredentialsIssued(access, locked);
    }

    // ---- human refresh: single-TX classification return ----

    /**
     * Human refresh rotation. The routing probe (owner id + family id, detached scalars)
     * is supplied by the caller from outside any fencing transaction; this method takes
     * the fence in principal → family → token order and returns a sealed classification
     * without throwing after any revocation.
     */
    public AuthTokenService.RefreshOutcome refresh(
            AuthTokenService.RefreshSubjectProbe probe,
            String rawRefreshToken,
            String actorIp,
            String correlationId
    ) {
        requireNoCallerTransaction("refresh");
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.MISSING_REFRESH_COOKIE, AuthAuditService.ANONYMOUS_USERNAME);
        }
        if (probe == null
                || probe.kind() != AuthTokenService.RefreshSubjectKind.HUMAN
                || probe.principalId() == null) {
            // Unknown hash (or non-human routing leak): family unidentifiable, no revoke.
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.OTHER, AuthAuditService.UNKNOWN_USERNAME);
        }
        String hash = sha256Hex(rawRefreshToken.trim());
        UUID ownerId = probe.principalId();
        UUID familyId = probe.familyId();
        return transactionTemplate.execute(status -> rotateHumanInTx(hash, ownerId, familyId, actorIp, correlationId));
    }

    private AuthTokenService.RefreshOutcome rotateHumanInTx(
            String hash, UUID ownerId, UUID familyIdOrNull, String actorIp, String correlationId) {
        runRefreshHook();
        // Principal lock first.
        AppUser locked = appUserRepository.findByIdForUpdate(ownerId).orElse(null);
        if (locked == null) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.OTHER, AuthAuditService.UNKNOWN_USERNAME);
        }
        locked.getRoles().size();
        if (locked.getLsp() != null) {
            locked.getLsp().getStatus();
        }
        String ownerUsername = locked.getUsername();
        // Live subject gates first: a disabled subject reports its own status even when
        // the all-user fence already revoked its families (accurate code, still no
        // writes on this path).
        if (locked.getStatus() != UserStatus.ACTIVE) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.USER_INACTIVE, ownerUsername);
        }
        if (locked.getLsp() != null && locked.getLsp().getStatus() != LspStatus.ACTIVE) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.LSP_INACTIVE, ownerUsername);
        }
        // Legacy null-family rows force login: no fabricated backfill, no successor.
        if (familyIdOrNull == null) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.SESSION_INVALID_STATUS, ownerUsername);
        }
        // Family lock second (principal already held).
        AuthSession session = authSessionRepository.findByIdForUpdate(familyIdOrNull).orElse(null);
        if (session == null || !locked.getId().equals(session.getUserId())) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.SESSION_INVALID_STATUS, ownerUsername);
        }
        // Token lock third; ownership re-verified after the locks, never trusted from
        // the routing probe.
        RefreshToken existing = refreshTokenRepository.findByTokenHashForUpdate(hash).orElse(null);
        if (existing == null
                || existing.getAppUser() == null
                || !locked.getId().equals(existing.getAppUser().getId())
                || existing.getFamilyId() == null
                || !session.getId().equals(existing.getFamilyId())) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.OTHER, AuthAuditService.UNKNOWN_USERNAME);
        }
        // Required issuance metadata: a family row without stored versions/epoch is
        // malformed (no legitimate legacy family backfill exists) — force login.
        if (existing.getIssuedTokenVersion() == null
                || existing.getIssuedPasswordChangedAtMillis() == null
                || existing.getIssuedPolicyEpoch() == null) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.SESSION_INVALID_STATUS, ownerUsername);
        }
        if (session.isRevoked()) {
            // Presenting any token of a dead family: family-only revoke is idempotent, no
            // tv bump, no cookie. Caller writes the failure audit (no new state).
            refreshTokenRepository.revokeLiveFamilyRows(session.getId());
            authPrincipalCache.evictAppUser(ownerUsername);
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.TOKEN_REVOKED, ownerUsername);
        }
        // Key-policy epoch cutover: old refresh cannot mint new-policy credentials.
        String currentEpoch = sessionPolicyEpochProvider.currentEpoch();
        if (!currentEpoch.equals(session.getPolicyEpoch())
                || !currentEpoch.equals(existing.getIssuedPolicyEpoch())) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.SESSION_INVALID_STATUS, ownerUsername);
        }
        // Version check against the presented row's stored versions (not a stale context):
        // a password/reset/revoke landing after issuance already revoked these rows via
        // the all-user fence, so report revocation without extra writes.
        if (existing.getIssuedTokenVersion().longValue() != locked.getTokenVersion()) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.TOKEN_REVOKED, ownerUsername);
        }
        if (existing.getIssuedPasswordChangedAtMillis().longValue()
                    != locked.getPasswordChangedAt().toEpochMilli()) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.TOKEN_REVOKED, ownerUsername);
        }
        Instant now = Instant.now();
        if (existing.getExpiresAt().isBefore(now)) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.TOKEN_EXPIRED, ownerUsername);
        }
        if (!existing.isRevoked()) {
            // Live head: revoke the consumed parent FIRST, then insert its single
            // successor — the order the V126 live-head uniqueness invariant requires.
            RawRefresh raw = newRawRefreshToken();
            existing.markReplaced(raw.hash(), now);
            refreshTokenRepository.save(existing);
            refreshTokenRepository.flush();
            RefreshToken successor = new RefreshToken(raw.hash(), locked, raw.expiresAt());
            successor.attachFamily(session, locked.getTokenVersion(),
                    locked.getPasswordChangedAt().toEpochMilli(), currentEpoch);
            refreshTokenRepository.save(successor);
            TokenResponse access = authTokenService.mintHumanFamilyToken(familySpec(locked, session.getId()));
            authAuditService.recordTokenRefreshSuccess(
                    ownerUsername, locked.getId(), null, actorIp, correlationId);
            authPrincipalCache.evictAppUser(ownerUsername);
            return AuthTokenService.RefreshOutcome.success(
                    access, raw.value(), ownerUsername, locked.getId(), null);
        }
        // Revoked presentation: direct parent within tolerance is benign (no state change,
        // no cookie, caller audits); anything else is reuse → family-only revoke in this
        // same TX with an accurate failure audit (never a success row). The head lookup
        // is a single bounded row under the live-head uniqueness invariant.
        RefreshToken liveHead = refreshTokenRepository
                .findLiveHeadByFamilyIdForUpdate(session.getId()).orElse(null);
        boolean isDirectParent = existing.getReplacedByHash() != null
                && liveHead != null
                && existing.getReplacedByHash().equals(liveHead.getTokenHash());
        if (isDirectParent && existing.getRevokedAt() != null
                && Duration.between(existing.getRevokedAt(), now).compareTo(LOSER_TOLERANCE) <= 0) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.TOKEN_ROTATED, ownerUsername);
        }
        session.revoke(now);
        authSessionRepository.save(session);
        int revokedRows = refreshTokenRepository.revokeLiveFamilyRows(session.getId());
        authAuditService.recordSessionsRevoked(
                locked,
                ownerUsername,
                "Family reuse revocation",
                actorIp,
                correlationId,
                RevocationSource.FAMILY_REUSE,
                locked.getTokenVersion(),
                locked.getTokenVersion(),
                revokedRows);
        authAuditService.recordTokenRefreshFailure(
                ownerUsername, AuthEventFailureReason.TOKEN_REVOKED, actorIp, correlationId);
        authPrincipalCache.evictAppUser(ownerUsername);
        return AuthTokenService.RefreshOutcome.failureAudited(
                AuthEventFailureReason.TOKEN_REVOKED, ownerUsername);
    }

    // ---- machine refresh/logout (machine-owned shape, same fence discipline) ----

    /**
     * Machine refresh rotation: api_client principal lock first, then the token row,
     * then audit — the same fence discipline as the human path. No family, no sid, no
     * policy epoch on this branch.
     */
    public AuthTokenService.RefreshOutcome refreshMachine(
            AuthTokenService.RefreshSubjectProbe probe,
            String rawRefreshToken,
            String actorIp,
            String correlationId
    ) {
        requireNoCallerTransaction("refreshMachine");
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.MISSING_REFRESH_COOKIE, AuthAuditService.ANONYMOUS_USERNAME);
        }
        if (probe == null
                || probe.kind() != AuthTokenService.RefreshSubjectKind.MACHINE
                || probe.principalId() == null) {
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.OTHER, AuthAuditService.UNKNOWN_USERNAME);
        }
        String hash = sha256Hex(rawRefreshToken.trim());
        UUID clientId = probe.principalId();
        return transactionTemplate.execute(status -> {
            runRefreshHook();
            ApiClient locked = apiClientRepository.findByIdForUpdate(clientId).orElse(null);
            if (locked == null) {
                return AuthTokenService.RefreshOutcome.failure(
                        AuthEventFailureReason.OTHER, AuthAuditService.UNKNOWN_USERNAME);
            }
            String subject = locked.getClientId();
            RefreshToken existing = refreshTokenRepository.findByTokenHashForUpdate(hash).orElse(null);
            if (existing == null
                    || existing.getApiClient() == null
                    || !locked.getId().equals(existing.getApiClient().getId())) {
                return AuthTokenService.RefreshOutcome.failure(
                        AuthEventFailureReason.OTHER, AuthAuditService.UNKNOWN_USERNAME);
            }
            if (locked.getLsp() != null && locked.getLsp().getStatus() != LspStatus.ACTIVE) {
                return AuthTokenService.RefreshOutcome.failure(
                        AuthEventFailureReason.LSP_INACTIVE, subject);
            }
            if (locked.getStatus() != ApiClientStatus.ACTIVE) {
                return AuthTokenService.RefreshOutcome.failure(
                        AuthEventFailureReason.SESSION_INVALID_STATUS, subject);
            }
            // Machine refresh is retired — reject before any access mint and revoke the
            // presented legacy row when it belongs to this principal.
            if (existing != null
                    && existing.getApiClient() != null
                    && locked.getId().equals(existing.getApiClient().getId())
                    && !existing.isRevoked()) {
                existing.revoke();
                refreshTokenRepository.save(existing);
            }
            return AuthTokenService.RefreshOutcome.failure(
                    AuthEventFailureReason.TOKEN_REVOKED, subject);
        });
    }

    // ---- human logout: per-family revoke, sibling families survive ----

    /**
     * Family logout: revokes only the presented session family (plus its live rows) in
     * one TX with the logout audit, then the controller clears the cookie Path-exact.
     * Garbage/unknown cookies revoke nothing but still clear the cookie.
     */
    public AuthTokenService.RevokeOutcome logoutFamily(
            AuthTokenService.RefreshSubjectProbe probe,
            String rawRefreshToken,
            String actorIp,
            String correlationId
    ) {
        requireNoCallerTransaction("logoutFamily");
        if (rawRefreshToken == null || rawRefreshToken.isBlank()
                || probe == null
                || probe.kind() != AuthTokenService.RefreshSubjectKind.HUMAN
                || probe.principalId() == null) {
            authAuditService.recordLogout(
                    AuthAuditService.ANONYMOUS_USERNAME, null, actorIp, correlationId);
            return AuthTokenService.RevokeOutcome.empty();
        }
        String hash = sha256Hex(rawRefreshToken.trim());
        UUID ownerId = probe.principalId();
        UUID familyId = probe.familyId();
        return transactionTemplate.execute(status -> {
            // Principal lock first.
            AppUser locked = appUserRepository.findByIdForUpdate(ownerId).orElse(null);
            if (locked == null) {
                authAuditService.recordLogout(
                        AuthAuditService.ANONYMOUS_USERNAME, null, actorIp, correlationId);
                return AuthTokenService.RevokeOutcome.empty();
            }
            locked.getRoles().size();
            // Legacy sid-less rows have no family to revoke (forced-login lineage, no
            // backfill): revoke the single row only.
            if (familyId == null) {
                RefreshToken legacy = refreshTokenRepository.findByTokenHashForUpdate(hash).orElse(null);
                if (legacy != null
                        && legacy.getAppUser() != null
                        && locked.getId().equals(legacy.getAppUser().getId())
                        && !legacy.isRevoked()) {
                    legacy.revoke();
                    refreshTokenRepository.save(legacy);
                }
                authAuditService.recordLogout(locked.getUsername(), locked.getId(), actorIp, correlationId);
                authPrincipalCache.evictAppUser(locked.getUsername());
                return new AuthTokenService.RevokeOutcome(locked.getUsername(), locked.getId());
            }
            // Family lock second (principal already held).
            AuthSession session = authSessionRepository.findByIdForUpdate(familyId).orElse(null);
            if (session == null || !locked.getId().equals(session.getUserId())) {
                authAuditService.recordLogout(locked.getUsername(), locked.getId(), actorIp, correlationId);
                return new AuthTokenService.RevokeOutcome(locked.getUsername(), locked.getId());
            }
            // Token lock third; ownership re-verified (a garbage cookie matching no row of
            // this family still logs out the subject but revokes nothing).
            RefreshToken existing = refreshTokenRepository.findByTokenHashForUpdate(hash).orElse(null);
            boolean ownedByFamily = existing != null
                    && existing.getAppUser() != null
                    && locked.getId().equals(existing.getAppUser().getId())
                    && session.getId().equals(existing.getFamilyId());
            if (!session.isRevoked() && ownedByFamily) {
                Instant now = Instant.now();
                session.revoke(now);
                authSessionRepository.save(session);
                int revokedRows = refreshTokenRepository.revokeLiveFamilyRows(session.getId());
                authAuditService.recordSessionsRevoked(
                        locked,
                        locked.getUsername(),
                        "Family logout",
                        actorIp,
                        correlationId,
                        RevocationSource.FAMILY_LOGOUT,
                        locked.getTokenVersion(),
                        locked.getTokenVersion(),
                        revokedRows);
            }
            authAuditService.recordLogout(locked.getUsername(), locked.getId(), actorIp, correlationId);
            authPrincipalCache.evictAppUser(locked.getUsername());
            return new AuthTokenService.RevokeOutcome(locked.getUsername(), locked.getId());
        });
    }

    /**
     * Machine logout: revokes the single presented row in one TX with the logout audit.
     */
    public AuthTokenService.RevokeOutcome logoutMachine(
            AuthTokenService.RefreshSubjectProbe probe,
            String rawRefreshToken,
            String actorIp,
            String correlationId
    ) {
        requireNoCallerTransaction("logoutMachine");
        if (rawRefreshToken == null || rawRefreshToken.isBlank()
                || probe == null
                || probe.kind() != AuthTokenService.RefreshSubjectKind.MACHINE
                || probe.principalId() == null) {
            authAuditService.recordLogout(
                    AuthAuditService.ANONYMOUS_USERNAME, null, actorIp, correlationId);
            return AuthTokenService.RevokeOutcome.empty();
        }
        String hash = sha256Hex(rawRefreshToken.trim());
        UUID clientId = probe.principalId();
        return transactionTemplate.execute(status -> {
            ApiClient locked = apiClientRepository.findByIdForUpdate(clientId).orElse(null);
            RefreshToken existing = refreshTokenRepository.findByTokenHashForUpdate(hash).orElse(null);
            String subject = probe.subject();
            if (existing != null
                    && existing.getApiClient() != null
                    && locked != null
                    && locked.getId().equals(existing.getApiClient().getId())
                    && !existing.isRevoked()) {
                existing.revoke();
                refreshTokenRepository.save(existing);
            }
            authAuditService.recordLogout(subject, null, actorIp, correlationId);
            return new AuthTokenService.RevokeOutcome(subject, null);
        });
    }

    // ---- self-service password change: all-user fence + single-TX issuance ----

    /**
     * Self-service password change as an all-user fence with single-TX re-issuance.
     * The presented session (sid + tv + pwdv from the request's bearer, extracted by the
     * controller — never the cached {@code Authentication} snapshot) is revalidated
     * inside the principal fence together with ACTIVE status, lock state and LSP policy:
     * a request authenticated before an admin reset/disable fails closed instead of
     * overwriting the reset and minting a fresh session. On success the post-change
     * snapshot is minted (pwdv bump + tv bump), other devices log out.
     */
    public PasswordLoginIssued changePasswordAndIssue(
            String username,
            String newPassword,
            PresentedSession presented,
            String actorIp,
            String correlationId
    ) {
        requireNoCallerTransaction("changePasswordAndIssue");
        requireField(username, "username");
        requireField(newPassword, "newPassword");
        // Owner id resolved outside the fence (detached); the fence re-locks by id.
        UUID ownerId = appUserRepository.findByUsername(username).map(AppUser::getId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Password changes are only supported for managed users."));
        Runnable hook = prePasswordFenceHook;
        if (hook != null) {
            hook.run();
        }
        return transactionTemplate.execute(status -> {
            // Principal lock first.
            AppUser locked = appUserRepository.findByIdForUpdate(ownerId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Password changes are only supported for managed users."));
            locked.getRoles().size();
            if (locked.getLsp() != null) {
                locked.getLsp().getStatus();
            }
            // Fence revalidation before any mutation: a deferred request must not
            // overwrite an intervening admin reset/disable.
            if (locked.isLocked()) {
                throw new BadCredentialsException("Session is no longer valid; please sign in again.");
            }
            if (locked.getStatus() != UserStatus.ACTIVE) {
                throw new BadCredentialsException("Session is no longer valid; please sign in again.");
            }
            if (locked.getLsp() != null && locked.getLsp().getStatus() != LspStatus.ACTIVE) {
                throw new com.bhawana.lms.security.LspInactiveAuthenticationException();
            }
            revalidatePresentedSession(locked, presented);
            if (!locked.isPasswordChangeRequired()) {
                throw new IllegalArgumentException("Password change is not required for this account.");
            }
            if (passwordEncoder.matches(newPassword, locked.getPasswordHash())) {
                throw new IllegalArgumentException("New password must differ from the temporary password.");
            }
            locked.changePassword(passwordEncoder.encode(newPassword));
            long previousTv = locked.getTokenVersion();
            locked.revokeAllSessions();
            AppUser saved = appUserRepository.save(locked);
            authSessionRepository.revokeAllForUser(saved.getId());
            int revoked = refreshTokenRepository.revokeAllForUser(saved.getId());
            String epoch = sessionPolicyEpochProvider.currentEpoch();
            AuthSession session = authSessionRepository.save(new AuthSession(saved, epoch));
            TokenResponse access = authTokenService.mintHumanFamilyToken(familySpec(saved, session.getId()));
            RawRefresh raw = newRawRefreshToken();
            RefreshToken row = new RefreshToken(raw.hash(), saved, raw.expiresAt());
            row.attachFamily(session, saved.getTokenVersion(),
                    saved.getPasswordChangedAt().toEpochMilli(), epoch);
            refreshTokenRepository.save(row);
            authAuditService.recordPasswordChanged(saved, actorIp, correlationId);
            authAuditService.recordSessionsRevoked(
                    saved,
                    saved.getUsername(),
                    "Self-service password change",
                    actorIp,
                    correlationId,
                    RevocationSource.SELF_PASSWORD_CHANGE,
                    previousTv,
                    saved.getTokenVersion(),
                    revoked);
            authPrincipalCache.evictAppUser(saved.getUsername());
            return new PasswordLoginIssued(access, raw.value(), saved.getUsername(), session.getId());
        });
    }

    /**
     * Revalidates the presented bearer inside the fence. A sid must bind to a live family
     * owned by the locked subject; presented tv/pwdv must equal the locked versions.
     * All three claims are required. Internal callers and tests obey the same session
     * proof contract as HTTP requests; absence never bypasses the fence.
     */
    private void revalidatePresentedSession(AppUser locked, PresentedSession presented) {
        if (presented == null || presented.sidOrNull() == null
                || presented.tokenVersionOrNull() == null
                || presented.passwordChangedAtMillisOrNull() == null) {
            throw new BadCredentialsException("Session is no longer valid; please sign in again.");
        }
        if (presented.sidOrNull() != null) {
            UUID familyId;
            try {
                familyId = UUID.fromString(presented.sidOrNull().trim());
            } catch (IllegalArgumentException exception) {
                throw new BadCredentialsException("Session is no longer valid; please sign in again.");
            }
            AuthSession session = authSessionRepository.findByIdForUpdate(familyId).orElse(null);
            if (session == null || session.isRevoked() || !locked.getId().equals(session.getUserId())) {
                throw new BadCredentialsException("Session is no longer valid; please sign in again.");
            }
        }
        if (presented.tokenVersionOrNull() != null
                && presented.tokenVersionOrNull().longValue() != locked.getTokenVersion()) {
            throw new BadCredentialsException("Session is no longer valid; please sign in again.");
        }
        if (presented.passwordChangedAtMillisOrNull() != null
                && presented.passwordChangedAtMillisOrNull().longValue()
                    != locked.getPasswordChangedAt().toEpochMilli()) {
            throw new BadCredentialsException("Session is no longer valid; please sign in again.");
        }
    }

    // ---- helpers ----

    private void runRefreshHook() {
        Runnable hook = preRefreshLockHook;
        if (hook != null) {
            hook.run();
        }
    }

    private static AuthTokenService.HumanFamilyTokenSpec familySpec(AppUser locked, UUID familyId) {
        com.bhawana.lms.domain.Lsp lsp = locked.getLsp();
        return new AuthTokenService.HumanFamilyTokenSpec(
                locked.getUsername(),
                locked.getRoles().stream().map(role -> role.getCode().name()).toList(),
                locked.isPasswordChangeRequired(),
                locked.getPasswordChangedAt().toEpochMilli(),
                locked.getTokenVersion(),
                lsp == null ? null : lsp.getId().toString(),
                lsp == null ? null : lsp.getCode(),
                lsp == null ? null : lsp.getName(),
                familyId);
    }

    private String resolveLoginUsername(String email, AppUser user) {
        if (user != null) {
            return user.getUsername();
        }
        SecurityProperties.BootstrapUser bootstrapUser = securityProperties.getBootstrapUser();
        String configuredEmail = bootstrapUser.getEmail();
        String configuredUsername = bootstrapUser.getUsername();
        if (configuredEmail != null
                && email.equalsIgnoreCase(configuredEmail)
                && configuredUsername != null
                && !configuredUsername.isBlank()) {
            return configuredUsername.trim().toLowerCase();
        }
        return email;
    }

    private static boolean hasLspUiRole(AppUser user) {
        for (AppRole role : user.getRoles()) {
            RoleCode code = role.getCode();
            if (code == RoleCode.LSP_UI_READ || code == RoleCode.LSP_UI_WRITE) {
                return true;
            }
        }
        return false;
    }

    private static String requireField(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    private RawRefresh newRawRefreshToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String hash = sha256Hex(value);
        return new RawRefresh(value, hash,
                Instant.now().plus(securityProperties.getJwt().getRefreshTtl()));
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available.", exception);
        }
    }

    /** Version-checked proof captured from the exact credential snapshot before auth. */
    private record PreAuthProof(UUID userId, long tokenVersion, long passwordChangedAtMillis) {
    }

    /** In-fence login rejection: audited by the caller outside, then rethrown. */
    private static final class ClientCredentialsFailed extends RuntimeException {
        private final RuntimeException rethrowable;

        ClientCredentialsFailed(RuntimeException rethrowable) {
            super(rethrowable);
            this.rethrowable = rethrowable;
        }

        ClientCredentialsFailed(RuntimeException rethrowable, String ignoredSubject) {
            super(rethrowable);
            this.rethrowable = rethrowable;
        }

        RuntimeException rethrowable() {
            return rethrowable;
        }
    }

    private static final class LoginFailed extends RuntimeException {
        private final String username;
        private final AuthEventFailureReason reason;
        private final RuntimeException rethrowable;

        LoginFailed(String username, AuthEventFailureReason reason, RuntimeException rethrowable) {
            super(rethrowable);
            this.username = username;
            this.reason = reason;
            this.rethrowable = rethrowable;
        }

        String username() {
            return username;
        }

        AuthEventFailureReason reason() {
            return reason;
        }

        RuntimeException rethrowable() {
            return rethrowable;
        }
    }

    public record PasswordLoginIssued(TokenResponse tokenResponse, String rawRefreshToken, String username, UUID familyId) {
    }

    public record ClientCredentialsIssued(TokenResponse tokenResponse, ApiClient apiClient) {
    }

    private record MachinePreProof(UUID clientId, long tokenVersion, ApiClientStatus status) {
    }

    /**
     * Presented bearer material for password-change revalidation: the request's sid, tv
     * and pwdv claims extracted by the controller from the live JWT (never a cached
     * Authentication snapshot). Nulls mean "not presented".
     */
    public record PresentedSession(String sidOrNull, Long tokenVersionOrNull, Long passwordChangedAtMillisOrNull) {
    }

    private record RawRefresh(String value, String hash, Instant expiresAt) {
    }
}
