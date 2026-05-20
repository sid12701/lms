package com.bhawana.lms.repo;

import com.bhawana.lms.domain.WebhookEventOutbox;
import java.time.Instant;
import java.util.List;

public interface WebhookEventOutboxRepositoryCustom {

    List<WebhookEventOutbox> claimDispatchBatch(Instant now, int batchSize);
}
