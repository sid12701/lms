package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guardrail for layering: the service layer must not depend on the web layer. Request/response
 * contracts shared between controllers and services belong in {@code common.api}, not {@code web},
 * so that services never reach "up" into the HTTP adapter package.
 */
class ServiceLayeringArchitectureTest {

    @Test
    void servicesMustNotDependOnWebLayer() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms.service");

        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String targetName = dependency.getTargetClass().getName();
                String targetPackage = dependency.getTargetClass().getPackageName();
                if (targetPackage.equals("com.bhawana.lms.web")
                        || targetPackage.startsWith("com.bhawana.lms.web.")) {
                    violations.add(javaClass.getSimpleName() + " -> " + targetName);
                    continue;
                }
                if (targetPackage.equals("com.bhawana.lms.common.web")
                        || targetPackage.startsWith("com.bhawana.lms.common.web.")) {
                    violations.add(javaClass.getSimpleName() + " -> " + targetName);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Service classes must not depend on the web layer or common.web: " + violations);
        }
    }
}
