package com.bhawana.lms.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DisbursementIntentRepositoryCustom {

    List<UUID> claimBatch(Instant now, int batchSize, Instant leaseExpiresAt, String leaseOwner);
}
