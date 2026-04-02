package com.bhawana.lms.repo;

import com.bhawana.lms.domain.WebhookEventOutbox;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventOutboxRepository extends JpaRepository<WebhookEventOutbox, UUID> {

    List<WebhookEventOutbox> findTop50ByOrderByCreatedAtDesc();

    List<WebhookEventOutbox> findTop50ByLsp_IdOrderByCreatedAtDesc(UUID lspId);

    @Query("""
            select event
            from WebhookEventOutbox event
            where event.status = com.bhawana.lms.domain.WebhookEventOutboxStatus.PENDING
                or (
                    event.status = com.bhawana.lms.domain.WebhookEventOutboxStatus.RETRYABLE_FAILURE
                    and (event.nextAttemptAt is null or event.nextAttemptAt <= :now)
                )
            order by event.createdAt asc
            """)
    List<WebhookEventOutbox> findDispatchBatch(@Param("now") Instant now, Pageable pageable);
}
