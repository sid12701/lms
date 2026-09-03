package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.bhawana.lms.web.LspBorrowerApiController;
import com.bhawana.lms.web.LspLoanApiController;
import com.bhawana.lms.web.LspLoanApplicationApiController;
import com.bhawana.lms.web.LspOptionsController;
import com.bhawana.lms.web.LspProductApiController;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * C1 guardrail: partner LSP API paths must not widen to admin scope except at named
 * perimeter boundaries (ADR 0005).
 */
class LspTenantElevationArchitectureTest {

    private static final Set<Class<?>> LSP_API_CONTROLLERS = Set.of(
            LspLoanApplicationApiController.class,
            LspLoanApiController.class,
            LspBorrowerApiController.class,
            LspProductApiController.class,
            LspOptionsController.class
    );

    private static final Map<String, String> ADMIN_ELEVATION_ALLOWLIST = Map.ofEntries(
            Map.entry(
                    "com.bhawana.lms.service.BorrowerOnboardingService",
                    "Cross-tenant PAN/mobile dedup and visibility grant (ADR 0005)."
            ),
            Map.entry(
                    "com.bhawana.lms.service.BorrowerActiveLoanChecker",
                    "Cross-LSP open-loan dedup reads (ADR 0005)."
            ),
            Map.entry(
                    "com.bhawana.lms.service.OpsAlertService",
                    "ops_alert is not granted to the tenant role (V45)."
            ),
            Map.entry(
                    "com.bhawana.lms.service.LspValidationAuditService",
                    "LSP schedule-violation ops alerts (V45)."
            ),
            Map.entry(
                    "com.bhawana.lms.service.BorrowerBankDetailsService",
                    "Disbursement mismatch audit rows and admin bank-detail updates only."
            ),
            Map.entry(
                    "com.bhawana.lms.service.IdempotencyExecutionCoordinator",
                    "Admin idempotency path only; LSP path uses ScopePreservingTransactionExecutor."
            ),
            Map.entry(
                    "com.bhawana.lms.service.BorrowerPiiRevealAuditService",
                    "PII reveal audit rows are admin-owned (V45)."
            )
    );

    @Test
    void lspApiSurfaceMustNotUseAdminElevationOutsideAllowlist() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        Set<JavaClass> reachable = collectReachableClasses(classes);
        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : reachable) {
            if (javaClass.getPackageName().startsWith("com.bhawana.lms.tenant")) {
                continue;
            }
            if (ADMIN_ELEVATION_ALLOWLIST.containsKey(javaClass.getName())) {
                continue;
            }
            recordAdminExecutorDependencyViolations(javaClass, violations);
            recordTenantScopedAdminCallViolations(javaClass, violations);
        }

        if (!violations.isEmpty()) {
            fail("LSP API surface must not use admin elevation outside the allowlist: " + violations);
        }
    }

    private static Set<JavaClass> collectReachableClasses(com.tngtech.archunit.core.domain.JavaClasses classes) {
        Set<JavaClass> reachable = new HashSet<>();
        Queue<JavaClass> pending = new ArrayDeque<>();
        for (Class<?> controllerType : LSP_API_CONTROLLERS) {
            JavaClass controller = classes.get(controllerType);
            pending.add(controller);
            reachable.add(controller);
        }

        while (!pending.isEmpty()) {
            JavaClass current = pending.remove();
            for (Dependency dependency : current.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (!target.getPackageName().startsWith("com.bhawana.lms")) {
                    continue;
                }
                if (reachable.add(target)) {
                    pending.add(target);
                }
            }
        }
        return reachable;
    }

    private static void recordAdminExecutorDependencyViolations(JavaClass javaClass, List<String> violations) {
        for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
            if (AdminScopedTransactionExecutor.class.getName().equals(dependency.getTargetClass().getName())) {
                violations.add(javaClass.getName() + " depends on AdminScopedTransactionExecutor");
            }
        }
    }

    private static void recordTenantScopedAdminCallViolations(JavaClass javaClass, List<String> violations) {
        for (JavaMethod method : javaClass.getMethods()) {
            if (!method.getOwner().equals(javaClass)) {
                continue;
            }
            for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                String ownerName = call.getTargetOwner().getName();
                String methodName = call.getName();
                if (!TenantScopedExecution.class.getName().equals(ownerName)) {
                    continue;
                }
                if ("callAsAdmin".equals(methodName) || "runAsAdmin".equals(methodName)) {
                    violations.add(javaClass.getName() + "#" + method.getName() + " -> TenantScopedExecution." + methodName);
                }
            }
        }
    }
}
