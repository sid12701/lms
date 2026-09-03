package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
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

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LspRepository lspRepository;

    private UUID lspId;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        lspApiIdempotencyRecordRepository.deleteAllInBatch();
        Lsp lsp = lspRepository.saveAndFlush(new Lsp(
                "RACE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Race Test LSP",
                LspStatus.ACTIVE
        ));
        lspId = lsp.getId();
    }

    @Test
    void concurrentExecuteWithSameKeyPersistsSingleRecord() throws Exception {
        String operationKey = "race-test";
        String idempotencyKey = UUID.randomUUID().toString();
        record RequestBody(String value) {
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                tasks.add(() -> asTenant(lspId, () -> lspApiIdempotencyService.execute(
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
            assertTrue(asTenant(lspId, () -> lspApiIdempotencyRecordRepository
                    .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, idempotencyKey)
                    .isPresent()));
        }
    }

    @Test
    void executeRejectsSameKeyWithDifferentBody() {
        String operationKey = "mismatch-test";
        String idempotencyKey = UUID.randomUUID().toString();
        record RequestBody(String value) {
        }

        asTenant(lspId, () -> lspApiIdempotencyService.execute(
                lspId,
                operationKey,
                idempotencyKey,
                new RequestBody("first"),
                String.class,
                () -> "first-response"
        ));

        try {
            asTenant(lspId, () -> lspApiIdempotencyService.execute(
                    lspId,
                    operationKey,
                    idempotencyKey,
                    new RequestBody("second"),
                    String.class,
                    () -> "second-response"
            ));
        } catch (com.bhawana.lms.common.api.error.ApiConflictException exception) {
            assertEquals("IDEMPOTENCY_CONFLICT", exception.getErrorCode());
            return;
        }
        throw new AssertionError("expected IDEMPOTENCY_CONFLICT");
    }

    @Test
    void serializationFailureRollsBackBusinessStateWithIdempotencyCompletion() {
        // Completing an idempotent call must not leave a COMPLETED ledger row when response
        // serialization fails. Tenant-scoped concurrent/mismatch cases above cover C1 paths.
        String operationKey = "atomicity-test";
        String idempotencyKey = UUID.randomUUID().toString();

        assertThrows(IllegalStateException.class, () -> asTenant(lspId, () -> lspApiIdempotencyService.execute(
                lspId,
                operationKey,
                idempotencyKey,
                new RequestBody("same-body"),
                CyclicResponse.class,
                CyclicResponse::new
        )));

        assertTrue(asTenant(lspId, () -> lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, idempotencyKey)
                .isEmpty()));
    }

    private static <T> T asTenant(UUID tenantLspId, Supplier<T> action) {
        TenantDataAccessContextHolder.useTenant(tenantLspId);
        try {
            return action.get();
        } finally {
            TenantDataAccessContextHolder.clear();
        }
    }

    private record RequestBody(String value) {
    }

    private static final class CyclicResponse {
        public CyclicResponse getSelf() {
            return this;
        }
    }
}
