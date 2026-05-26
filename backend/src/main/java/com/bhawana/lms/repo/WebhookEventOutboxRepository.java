package com.bhawana.lms.repo;

import com.bhawana.lms.domain.WebhookEventOutbox;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventOutboxRepository extends JpaRepository<WebhookEventOutbox, UUID>, WebhookEventOutboxRepositoryCustom {

    List<WebhookEventOutbox> findTop50ByOrderByCreatedAtDesc();

    List<WebhookEventOutbox> findTop50ByLsp_IdOrderByCreatedAtDesc(UUID lspId);

    List<WebhookEventOutbox> findTop200ByLoanApplicationIdOrderByCreatedAtDesc(UUID loanApplicationId);
}
