package com.bhawana.lms.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DisbursementIntentRepositoryCustom {

    List<ClaimToken> claimBatch(Instant now, int batchSize, Instant leaseExpiresAt, String leaseOwner);

    java.util.Optional<ClaimToken> claimSingle(
            java.util.UUID intentId, Instant now, Instant leaseExpiresAt, String leaseOwner);
}
