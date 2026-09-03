package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.MockDisbursementOutcome;
import org.junit.jupiter.api.Test;

class LoanApplicationOpsModelsTest {

    @Test
    void mockDisbursementOutcomeRequestExposesOutcome() {
        MockDisbursementOutcomeRequest request =
                new MockDisbursementOutcomeRequest(MockDisbursementOutcome.PENDING_RECONCILIATION);

        assertThat(request.outcome()).isEqualTo(MockDisbursementOutcome.PENDING_RECONCILIATION);
    }
}
