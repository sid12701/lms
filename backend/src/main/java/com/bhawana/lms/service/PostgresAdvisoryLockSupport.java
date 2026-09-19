package com.bhawana.lms.service;

import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mutual exclusion for scheduled jobs via a transaction-scoped PostgreSQL advisory lock.
 *
 * <p>The lock is acquired with {@code pg_try_advisory_xact_lock} inside the same transaction —
 * and therefore the same pooled connection — that runs the protected work, and is released by
 * the database at commit or rollback. A session-level {@code pg_try_advisory_lock} paired with a
 * later {@code pg_advisory_unlock} is unsafe here on purpose: JdbcTemplate can check out a
 * different pooled connection for the release, which leaks the lock or reports an unlock that
 * never happened, and the same session can then re-acquire it while unrelated work assumes it is
 * free. Transaction-scoped locks also survive connection-pooling modes (for example PgBouncer
 * transaction pooling) where session state does not.
 */
@Component
public class PostgresAdvisoryLockSupport {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryLockSupport.class);

    private final JdbcTemplate jdbcTemplate;

    public PostgresAdvisoryLockSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs {@code work} inside a dedicated transaction while holding the advisory lock
     * {@code lockId} on that transaction's connection.
     *
     * <p>{@code REQUIRES_NEW} pins the lock lifetime to exactly this run even when a caller
     * already has a transaction open: the lock can never silently extend to an outer transaction
     * boundary. The callback must return a non-null value so that an empty result unambiguously
     * means "lock not acquired".
     *
     * @return the work result, or empty when another session already holds the lock
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> Optional<T> runWithAdvisoryLock(long lockId, String jobName, Supplier<T> work) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_xact_lock(?)",
                Boolean.class,
                lockId
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        log.debug("scheduled_job_lock_acquired jobName={} lockId={}", jobName, lockId);
        try {
            return Optional.of(work.get());
        } catch (RuntimeException failure) {
            log.warn("scheduled_job_run_failed jobName={} lockId={}", jobName, lockId, failure);
            throw failure;
        }
    }
}
