package com.bhawana.lms.web;

import com.bhawana.lms.service.DisbursementReconciliationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit reconciliation queue API. Exposes bounded queue pages plus counts AND oldest
 * age for all unresolved states (an external metrics exporter may build on this surface; no exporter here).
 */
@RestController
@RequestMapping("/api/v1/internal/ops/disbursement-reconciliation")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
@Validated
public class DisbursementReconciliationController {

    private final DisbursementReconciliationService disbursementReconciliationService;

    public DisbursementReconciliationController(
            DisbursementReconciliationService disbursementReconciliationService) {
        this.disbursementReconciliationService = disbursementReconciliationService;
    }

    @GetMapping("/queue")
    public List<DisbursementReconciliationService.QueueEntryView> queue(
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(50) int limit,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset
    ) {
        return disbursementReconciliationService.queuePage(limit, offset);
    }

    @GetMapping("/queue/summary")
    public DisbursementReconciliationService.QueueSummary queueSummary() {
        return disbursementReconciliationService.queueSummary();
    }

    @PostMapping("/queue/{loanAccountId}/claim")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public void claimEntry(
            Authentication authentication,
            @PathVariable UUID loanAccountId,
            @RequestBody ClaimEntryRequest request
    ) {
        String owner = request.owner() == null || request.owner().isBlank()
                ? authentication.getName() : request.owner().trim();
        disbursementReconciliationService.claimEntry(loanAccountId, owner);
    }

    public record ClaimEntryRequest(String owner) {
    }
}
