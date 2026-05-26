package com.bhawana.lms.repo;

import com.bhawana.lms.domain.WebhookEventDeliveryAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventDeliveryAttemptRepository extends JpaRepository<WebhookEventDeliveryAttempt, UUID> {

    List<WebhookEventDeliveryAttempt> findByOutboxEvent_IdOrderByCreatedAtDesc(UUID outboxEventId);

    java.util.Optional<WebhookEventDeliveryAttempt> findFirstByOutboxEvent_IdOrderByCreatedAtDesc(UUID outboxEventId);
}
