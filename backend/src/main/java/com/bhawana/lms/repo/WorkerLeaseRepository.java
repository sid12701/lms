package com.bhawana.lms.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable worker lease (M07): atomic claim with expiry-based reclaim and a fencing sequence
 * that lets a displaced owner detect it lost the job before writing more state. Every call is
 * a single statement in its own transaction — the lease exists so a multi-batch job does not
 * need one long transaction or advisory lock to stay singleton.
 */
@Repository
public class WorkerLeaseRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public WorkerLeaseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims {@code jobName} for {@code owner} until {@code expiresAt}, reclaiming the row
     * when the previous owner's lease already lapsed. Returns the fencing sequence the caller
     * must pass to {@link #renew} and {@link #release}, or empty when another live owner
     * holds the job.
     */
    public OptionalLong tryClaim(String jobName, String owner, Instant expiresAt) {
        List<Long> claimed = jdbc.query(
                """
                insert into worker_lease (job_name, owner, fencing_seq, claimed_at, expires_at, updated_at)
                values (:jobName, :owner, 1, now(), :expiresAt, now())
                on conflict (job_name) do update
                    set owner = :owner,
                        fencing_seq = worker_lease.fencing_seq + 1,
                        claimed_at = now(),
                        expires_at = :expiresAt,
                        updated_at = now()
                where worker_lease.expires_at < now()
                returning fencing_seq
                """,
                new MapSqlParameterSource()
                        .addValue("jobName", jobName)
                        .addValue("owner", owner)
                        .addValue("expiresAt", Timestamp.from(expiresAt)),
                (rs, rowNum) -> rs.getLong("fencing_seq")
        );
        return claimed.isEmpty() ? OptionalLong.empty() : OptionalLong.of(claimed.getFirst());
    }

    /**
     * Extends the lease while this fenced owner still holds it. A {@code false} return means
     * the job was reclaimed by another worker — the caller must stop writing.
     */
    public boolean renew(String jobName, String owner, long fencingSeq, Instant expiresAt) {
        return jdbc.update(
                """
                update worker_lease
                set expires_at = :expiresAt,
                    updated_at = now()
                where job_name = :jobName
                  and owner = :owner
                  and fencing_seq = :fencingSeq
                """,
                new MapSqlParameterSource()
                        .addValue("jobName", jobName)
                        .addValue("owner", owner)
                        .addValue("fencingSeq", fencingSeq)
                        .addValue("expiresAt", Timestamp.from(expiresAt))
        ) == 1;
    }

    /**
     * Frees the job for the next run while keeping the row (and its fencing history). Only
     * this fenced owner can release; a displaced owner cannot shorten a successor's lease.
     */
    public void release(String jobName, String owner, long fencingSeq) {
        jdbc.update(
                """
                update worker_lease
                set expires_at = now(),
                    updated_at = now()
                where job_name = :jobName
                  and owner = :owner
                  and fencing_seq = :fencingSeq
                """,
                new MapSqlParameterSource()
                        .addValue("jobName", jobName)
                        .addValue("owner", owner)
                        .addValue("fencingSeq", fencingSeq)
        );
    }
}
