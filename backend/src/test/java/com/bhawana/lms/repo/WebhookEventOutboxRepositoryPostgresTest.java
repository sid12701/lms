package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebhookEventOutboxRepositoryPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimDispatchBatchSkipsRowsLockedByAnotherWorker() throws Exception {
        Lsp lsp = lspRepository.save(new Lsp("WEBHOOK-PG", "Webhook Finance", LspStatus.ACTIVE));
        webhookEventOutboxRepository.saveAllAndFlush(List.of(
                event(lsp, "aggregate-1"),
                event(lsp, "aggregate-2"),
                retryableEvent(lsp, "aggregate-3", Instant.now().minusSeconds(60))
        ));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstWorkerClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);

        try {
            Instant claimExpiresAt = Instant.now().plusSeconds(300);

            Future<List<UUID>> firstWorker = executor.submit(() -> transactionTemplate.execute(status -> {
                List<UUID> claimed = webhookEventOutboxRepository
                        .claimDispatchBatch(Instant.now(), 2, claimExpiresAt)
                        .stream()
                        .map(WebhookEventOutbox::getId)
                        .toList();
                firstWorkerClaimed.countDown();
                awaitLatch(releaseFirstWorker);
                return claimed;
            }));

            assertThat(firstWorkerClaimed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<UUID>> secondWorker = executor.submit(() -> transactionTemplate.execute(status ->
                    webhookEventOutboxRepository.claimDispatchBatch(Instant.now(), 2, claimExpiresAt).stream()
                            .map(WebhookEventOutbox::getId)
                            .toList()
            ));

            List<UUID> secondClaimedIds = secondWorker.get(5, TimeUnit.SECONDS);
            releaseFirstWorker.countDown();
            List<UUID> firstClaimedIds = firstWorker.get(5, TimeUnit.SECONDS);

            assertThat(firstClaimedIds).hasSize(2);
            assertThat(secondClaimedIds).hasSize(1);
            assertThat(secondClaimedIds).doesNotContainAnyElementsOf(firstClaimedIds);
        } finally {
            releaseFirstWorker.countDown();
            executor.shutdownNow();
        }
    }

    private WebhookEventOutbox event(Lsp lsp, String aggregateId) {
        // loan_application_id is left null: this test exercises claim-batch
        // concurrency, not application linkage, and the FK added by V66
        // rejects synthetic ids that don't reference a real loan_application.
        return new WebhookEventOutbox(
                lsp,
                WebhookEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                aggregateId,
                null,
                WebhookEventOutboxStatus.PENDING,
                "{\"aggregateId\":\"" + aggregateId + "\"}",
                "correlation-" + aggregateId
        );
    }

    private WebhookEventOutbox retryableEvent(Lsp lsp, String aggregateId, Instant nextAttemptAt) {
        WebhookEventOutbox event = event(lsp, aggregateId);
        event.markRetryableFailure(Instant.now().minusSeconds(120), nextAttemptAt, "Temporary failure");
        return event;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for claim coordination.", exception);
        }
    }
}
