package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DisbursementIntentMetricsTest {

    @Test
    void exposesUnknownIntentCountAndOldestAge() {
        DisbursementIntentRepository repository = mock(DisbursementIntentRepository.class);
        when(repository.countByState(DisbursementIntentState.UNKNOWN)).thenReturn(3L);
        when(repository.findOldestUpdatedAtByState(DisbursementIntentState.UNKNOWN))
                .thenReturn(Optional.of(Instant.now().minusSeconds(120)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new DisbursementIntentMetrics(repository, registry);

        assertEquals(3.0, registry.get("lms.disbursement.intent.unknown.count").gauge().value());
        double age = registry.get("lms.disbursement.intent.unknown.oldest_age_seconds").gauge().value();
        assertTrue(age >= 120 && age < 125);
    }
}
