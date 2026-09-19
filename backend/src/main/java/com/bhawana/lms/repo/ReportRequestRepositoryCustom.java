package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ReportRequest;
import java.time.Instant;
import java.util.List;

public interface ReportRequestRepositoryCustom {

    /**
     * Claims up to {@code batchSize} processable rows for {@code owner}: PENDING work plus
     * PROCESSING rows whose lease already expired (crashed-worker recovery). The claim writes
     * owner, lease expiry and an incremented fencing attempt in one statement and commits with
     * the surrounding transaction, so the owner survives a crash instead of silently reverting.
     */
    List<ReportRequest> claimBatchForProcessing(String owner, Instant leaseExpiresAt, int batchSize);
}
