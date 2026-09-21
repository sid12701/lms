package com.bhawana.lms.service;

import com.bhawana.lms.config.ScheduledJobThreadingConfig;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded purge of dead refresh credentials (L04).
 *
 * <p>Policy: a {@code refresh_token} row is deleted only once {@code expires_at}
 * is more than {@code expired-retention-days} in the past. That predicate can never
 * remove evidence family reuse detection still needs:
 *
 * <ul>
 *   <li>A live (unexpired, unrevoked) head is never past expiry.</li>
 *   <li>The reuse signal is a revoked row presented while it is still unexpired —
 *   those rows are kept. A replayed token that is <em>already expired</em> fails as
 *   {@code TOKEN_EXPIRED} before the reuse branch runs, so purging it after the
 *   retention margin changes a failure code, never a security decision.</li>
 *   <li>{@code auth_session} family rows and auth audit events are never purged —
 *   they are the session/revocation audit trail.</li>
 * </ul>
 *
 * <p>Each batch is its own short transaction of at most {@code batch-size} rows via
 * {@code idx_refresh_token_expires}; a run stops after {@code max-batches-per-run}
 * so a backlog drains over subsequent ticks instead of one long delete.
 */
@Component
public class RefreshTokenRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRetentionWorker.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final JobObservabilitySupport jobObservability;
    private final SecurityProperties securityProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public RefreshTokenRetentionWorker(
            RefreshTokenRepository refreshTokenRepository,
            JobObservabilitySupport jobObservability,
            SecurityProperties securityProperties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jobObservability = jobObservability;
        this.securityProperties = securityProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            fixedDelayString = "${app.security.refresh-token-retention.purge-fixed-delay-ms:3600000}",
            scheduler = ScheduledJobThreadingConfig.MAINTENANCE_TASK_SCHEDULER
    )
    public void purgeExpiredTokens() {
        SecurityProperties.RefreshTokenRetention retention = securityProperties.getRefreshTokenRetention();
        if (!retention.isEnabled()) {
            return;
        }
        jobObservability.run("refresh-token-retention",
                () -> TenantScopedExecution.runAsAdmin(this::purgeExpiredTokensUnderAdminScope));
    }

    void purgeExpiredTokensUnderAdminScope() {
        SecurityProperties.RefreshTokenRetention retention = securityProperties.getRefreshTokenRetention();
        Instant cutoff = clock.instant()
                .minus(retention.getExpiredRetentionDays(), ChronoUnit.DAYS);
        int total = 0;
        for (int batch = 0; batch < retention.getMaxBatchesPerRun(); batch++) {
            Integer deleted = transactionTemplate.execute(status ->
                    refreshTokenRepository.deleteExpiredBatch(cutoff, retention.getBatchSize()));
            total += deleted == null ? 0 : deleted;
            if (deleted == null || deleted < retention.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info(
                    "refresh_tokens_purged deleted={} retentionDays={} cutoff={}",
                    total,
                    retention.getExpiredRetentionDays(),
                    cutoff
            );
        }
    }
}
