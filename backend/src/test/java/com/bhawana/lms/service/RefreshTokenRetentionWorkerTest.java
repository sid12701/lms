package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthSession;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthSessionRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L04 acceptance (real Postgres): the bounded refresh-token purge removes only rows
 * that are both expired and past the post-expiry retention margin. It must never
 * remove a live token, a revoked-but-unexpired row (the evidence the family reuse
 * detector reads when a rotated credential is replayed), or the session family and
 * audit lineage those rows hang off.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.refresh-token-retention.batch-size=2")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class RefreshTokenRetentionWorkerTest {

    @Autowired
    private RefreshTokenRetentionWorker worker;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppRoleRepository appRoleRepository;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private com.bhawana.lms.repo.LspRepository lspRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void tearDown() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void purgeKeepsLiveAndReusableEvidenceAndDropsOnlyLongExpiredRows() {
        AppUser user = seedUser("purge.user");
        AuthSession family = TenantScopedExecution.callAsAdmin(
                () -> authSessionRepository.save(new AuthSession(user, "test-epoch")));

        // Family lineage: consumed parent (revoked, unexpired, replaced by the head)
        // is exactly the row reuse detection compares on replay — it must survive.
        // Rotation order matters — V126's partial unique index allows only one
        // unrevoked row per family, so the parent is saved as the live head, replaced,
        // and only then is the successor inserted. The extra expired sessions below
        // each get their own family for the same reason.
        RefreshToken revokedParent = saveToken(user, family, "revoked-parent", Instant.now().plus(3, ChronoUnit.DAYS));
        RefreshToken liveHead = new RefreshToken("live-head", user, Instant.now().plus(7, ChronoUnit.DAYS));
        revokedParent.markReplaced(liveHead.getTokenHash(), Instant.now());
        TenantScopedExecution.runAsAdmin(() -> {
            refreshTokenRepository.save(revokedParent);
            liveHead.attachFamily(family, 0L, 0L, "test-epoch");
            refreshTokenRepository.save(liveHead);
        });

        // Expired inside the 30-day post-expiry retention margin: kept for now.
        RefreshToken recentExpired = saveToken(user, newFamily(user), "recent-expired", Instant.now().minus(1, ChronoUnit.DAYS));

        // Expired beyond the margin — the only rows the purge may take.
        RefreshToken staleExpired = saveToken(user, newFamily(user), "stale-expired", Instant.now().minus(60, ChronoUnit.DAYS));
        RefreshToken staleRevoked = saveToken(user, newFamily(user), "stale-revoked", Instant.now().minus(90, ChronoUnit.DAYS));
        staleRevoked.revoke();
        refreshTokenRepository.save(staleRevoked);

        ApiClient client = seedApiClient();
        RefreshToken staleMachine = TenantScopedExecution.callAsAdmin(() -> refreshTokenRepository.save(
                new RefreshToken("stale-machine", client, Instant.now().minus(45, ChronoUnit.DAYS))));

        TenantScopedExecution.runAsAdmin(worker::purgeExpiredTokensUnderAdminScope);

        Set<UUID> surviving = Set.copyOf(refreshTokenRepository.findAll().stream()
                .map(RefreshToken::getId)
                .toList());
        assertThat(surviving).contains(
                liveHead.getId(), revokedParent.getId(), recentExpired.getId());
        assertThat(surviving).doesNotContain(
                staleExpired.getId(), staleRevoked.getId(), staleMachine.getId());

        // The session family survives intact — it is the revocation/audit lineage,
        // not transient credential material.
        assertThat(authSessionRepository.findById(family.getId())).isPresent();
    }

    @Test
    void purgeIsBoundedPerBatch() {
        AppUser user = seedUser("bounded.user");
        Instant beyondMargin = Instant.now().minus(45, ChronoUnit.DAYS);
        for (int i = 0; i < 5; i++) {
            // No family attached: legacy pre-V125 human rows and machine rows carry
            // NULL family_id, which the live-head partial index never conflicts on.
            saveToken(user, null, "expired-" + i, beyondMargin.minus(i, ChronoUnit.HOURS));
        }

        // batch-size=2 (test property): each call deletes at most one batch, so the
        // backlog drains incrementally rather than in one unbounded statement.
        int first = new TransactionTemplate(transactionManager).execute(status ->
                refreshTokenRepository.deleteExpiredBatch(Instant.now(), 2));
        assertThat(first).isEqualTo(2);

        TenantScopedExecution.runAsAdmin(worker::purgeExpiredTokensUnderAdminScope);
        assertThat(refreshTokenRepository.count()).isZero();
    }

    private AppUser seedUser(String username) {
        return TenantScopedExecution.callAsAdmin(() -> {
            List<AppRole> roles = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER));
            return appUserRepository.save(new AppUser(
                    username,
                    username + "@bhawana.local",
                    "not-a-real-hash",
                    UserStatus.ACTIVE,
                    null,
                    Set.copyOf(roles)));
        });
    }

    private ApiClient seedApiClient() {
        UUID lspId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status, token_version, enforce_ui_allowlist, enforce_api_allowlist, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', 0, false, false, current_timestamp, current_timestamp)",
                lspId,
                "PURGE-" + lspId.toString().substring(0, 8).toUpperCase(),
                "Purge Test LSP"
        );
        Lsp lsp = TenantScopedExecution.callAsAdmin(() -> lspRepository.findById(lspId).orElseThrow());
        return TenantScopedExecution.callAsAdmin(() -> apiClientRepository.save(
                new ApiClient("purge-client", lsp, "Purge Client", null, "hash", ApiClientStatus.ACTIVE)));
    }

    private AuthSession newFamily(AppUser user) {
        return TenantScopedExecution.callAsAdmin(
                () -> authSessionRepository.save(new AuthSession(user, "test-epoch")));
    }

    private RefreshToken saveToken(AppUser user, AuthSession family, String hash, Instant expiresAt) {
        return TenantScopedExecution.callAsAdmin(() -> {
            RefreshToken token = new RefreshToken(hash, user, expiresAt);
            if (family != null) {
                token.attachFamily(family, 0L, 0L, "test-epoch");
            }
            return refreshTokenRepository.save(token);
        });
    }
}
