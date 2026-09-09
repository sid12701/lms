package com.bhawana.lms.web;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record ReconcileDisbursementRequest(
        @NotNull UUID observationId
) {
}
