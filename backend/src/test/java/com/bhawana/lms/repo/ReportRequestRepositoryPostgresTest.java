package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportRequestStatus;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.time.LocalDate;
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
class ReportRequestRepositoryPostgresTest extends PostgresDataJpaTestSupport {

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimBatchForProcessingSkipsRowsLockedByAnotherWorker() throws Exception {
        reportRequestRepository.saveAllAndFlush(List.of(
                request("ops-1"),
                request("ops-2"),
                request("ops-3")
        ));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstWorkerClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);

        try {
            Future<List<UUID>> firstWorker = executor.submit(() -> transactionTemplate.execute(status -> {
                List<UUID> claimed = reportRequestRepository.claimBatchForProcessing(
                                List.of(ReportRequestStatus.PENDING),
                                2
                        ).stream()
                        .map(ReportRequest::getId)
                        .toList();
                firstWorkerClaimed.countDown();
                awaitLatch(releaseFirstWorker);
                return claimed;
            }));

            assertThat(firstWorkerClaimed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<UUID>> secondWorker = executor.submit(() -> transactionTemplate.execute(status ->
                    reportRequestRepository.claimBatchForProcessing(List.of(ReportRequestStatus.PENDING), 2).stream()
                            .map(ReportRequest::getId)
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

    private ReportRequest request(String requestedByUsername) {
        return new ReportRequest(
                ReportType.PORTFOLIO_MIS,
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                requestedByUsername,
                requestedByUsername + "@example.com"
        );
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
