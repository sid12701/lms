package com.bhawana.lms.repo;

import com.bhawana.lms.domain.WebhookOutboxRedriveAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookOutboxRedriveAuditRepository extends JpaRepository<WebhookOutboxRedriveAudit, UUID> {

    List<WebhookOutboxRedriveAudit> findByWebhookEvent_IdOrderByCreatedAtDesc(UUID webhookEventId);
}
