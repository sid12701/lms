package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Persists ops-side audit artifacts for LSP validation failures. Must run on the admin
 * datasource — see {@code V45__tenant_isolation_hardening.sql} (ops_alert is not granted
 * to the tenant role).
 */
@Service
public class LspValidationAuditService {

    private final OpsAlertEmitters opsAlertEmitters;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;

    public LspValidationAuditService(
            OpsAlertEmitters opsAlertEmitters,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor
    ) {
        this.opsAlertEmitters = opsAlertEmitters;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
    }

    public void recordLspProvidedScheduleViolation(
            LoanApplication application,
            ScheduleViolationType violationType,
            String message,
            Map<String, String> details
    ) {
        adminScopedTransactionExecutor.run(() -> opsAlertEmitters.emitLspProvidedScheduleViolation(
                application,
                violationType,
                message,
                details
        ));
    }
}
