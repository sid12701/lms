package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdempotencyMetricsTest {

    @Test
    void exposesCombinedPendingCountAndOldestAge() {
        LspApiIdempotencyRecordRepository lspRepository = mock(LspApiIdempotencyRecordRepository.class);
        AdminApiIdempotencyRecordRepository adminRepository = mock(AdminApiIdempotencyRecordRepository.class);
        when(lspRepository.countByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY)).thenReturn(2L);
        when(adminRepository.countByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY)).thenReturn(1L);
        when(lspRepository.findOldestCreatedAtByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY))
                .thenReturn(Optional.of(Instant.now().minusSeconds(180)));
        when(adminRepository.findOldestCreatedAtByResponseBody(IdempotencyRecordState.PENDING_RESPONSE_BODY))
                .thenReturn(Optional.of(Instant.now().minusSeconds(60)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new IdempotencyMetrics(lspRepository, adminRepository, registry);

        assertEquals(3.0, registry.get("lms.idempotency.pending.count").gauge().value());
        double age = registry.get("lms.idempotency.pending.oldest_age_seconds").gauge().value();
        assertTrue(age >= 180 && age < 185);
    }
}
