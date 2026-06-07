package com.bhawana.lms.service;

import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookOutboxRedriveAudit;
import com.bhawana.lms.repo.WebhookOutboxRedriveAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookOutboxRedriveAuditService {

    private final WebhookOutboxRedriveAuditRepository repository;

    public WebhookOutboxRedriveAuditService(WebhookOutboxRedriveAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void recordRedrive(
            WebhookEventOutbox event,
            String actorUsername,
            String actorIp,
            String correlationId,
            int redriveCount
    ) {
        repository.save(new WebhookOutboxRedriveAudit(
                event,
                event.getLsp(),
                actorUsername,
                actorIp,
                correlationId,
                redriveCount
        ));
    }
}
