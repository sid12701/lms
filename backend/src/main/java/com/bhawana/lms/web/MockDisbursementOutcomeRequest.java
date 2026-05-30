package com.bhawana.lms.web;

import com.bhawana.lms.domain.MockDisbursementOutcome;
import jakarta.validation.constraints.NotNull;

public record MockDisbursementOutcomeRequest(
        @NotNull MockDisbursementOutcome outcome
) {
}
