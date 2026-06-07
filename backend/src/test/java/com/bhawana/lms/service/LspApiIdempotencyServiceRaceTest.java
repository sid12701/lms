package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LspApiIdempotencyServiceRaceTest {

    @Autowired
    private LspApiIdempotencyService lspApiIdempotencyService;

    @Autowired
    private LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;

    @Test
    void concurrentExecuteWithSameKeyPersistsSingleRecord() throws Exception {
        UUID lspId = UUID.randomUUID();
        String operationKey = "race-test";
        String idempotencyKey = UUID.randomUUID().toString();
        record RequestBody(String value) {
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                tasks.add(() -> TenantScopedExecution.callAsAdmin(() -> lspApiIdempotencyService.execute(
                        lspId,
                        operationKey,
                        idempotencyKey,
                        new RequestBody("same-body"),
                        String.class,
                        () -> "ok-" + idempotencyKey
                )));
            }

            List<Future<String>> futures = executor.invokeAll(tasks);
            List<String> responses = new ArrayList<>();
            for (Future<String> future : futures) {
                responses.add(future.get());
            }

            assertEquals(5, responses.size());
            assertEquals(1, responses.stream().distinct().count());
            assertTrue(lspApiIdempotencyRecordRepository
                    .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, idempotencyKey)
                    .isPresent());
        }
    }

    @Test
    void executeRejectsSameKeyWithDifferentBody() {
        UUID lspId = UUID.randomUUID();
        String operationKey = "mismatch-test";
        String idempotencyKey = UUID.randomUUID().toString();
        record RequestBody(String value) {
        }

        lspApiIdempotencyService.execute(
                lspId,
                operationKey,
                idempotencyKey,
                new RequestBody("first"),
                String.class,
                () -> "first-response"
        );

        try {
            lspApiIdempotencyService.execute(
                    lspId,
                    operationKey,
                    idempotencyKey,
                    new RequestBody("second"),
                    String.class,
                    () -> "second-response"
            );
        } catch (com.bhawana.lms.common.web.ApiConflictException exception) {
            assertEquals("IDEMPOTENCY_CONFLICT", exception.getErrorCode());
            return;
        }
        throw new AssertionError("expected IDEMPOTENCY_CONFLICT");
    }
}
