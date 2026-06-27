package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * B11 guardrail: partner-facing LSP controllers must not leak internal ops DTO types.
 */
class LspSurfaceArchitectureTest {

    private static final Set<String> BLOCKED_OPS_TYPES = Set.of(
            "com.bhawana.lms.web.LoanApplicationOpsController",
            "com.bhawana.lms.web.LoanApplicationOpsResponses",
            "com.bhawana.lms.web.LoanApplicationOpsApiTypes"
    );

    @Test
    void lspSurfaceMustNotReferenceOpsControllerTypes() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms.web");

        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!isLspSurfaceClass(javaClass)) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                recordViolationIfBlocked(javaClass, dependency.getTargetClass().getName(), violations);
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getOwner().equals(javaClass)) {
                    continue;
                }
                recordViolationIfBlocked(javaClass, method.getRawReturnType().getName(), violations);
                for (JavaParameter parameter : method.getParameters()) {
                    recordViolationIfBlocked(javaClass, parameter.getRawType().getName(), violations);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("LSP surface must not reference internal ops DTO types: " + violations);
        }
    }

    private static boolean isLspSurfaceClass(JavaClass javaClass) {
        if (!javaClass.isTopLevelClass()) {
            return false;
        }
        String simpleName = javaClass.getSimpleName();
        return simpleName.startsWith("Lsp") || simpleName.contains("LspLoan");
    }

    private static void recordViolationIfBlocked(JavaClass owner, String referencedType, List<String> violations) {
        if (BLOCKED_OPS_TYPES.contains(referencedType)
                || referencedType.startsWith("com.bhawana.lms.web.LoanApplicationOpsController$")
                || referencedType.startsWith("com.bhawana.lms.web.LoanApplicationOpsResponses$")
                || referencedType.startsWith("com.bhawana.lms.web.LoanApplicationOpsApiTypes$")) {
            violations.add(owner.getSimpleName() + " -> " + referencedType);
        }
    }
}
