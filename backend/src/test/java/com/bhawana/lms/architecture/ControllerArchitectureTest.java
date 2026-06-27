package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guardrails for thin controllers: HTTP adapters delegate persistence and transactions to services.
 */
class ControllerArchitectureTest {

    @Test
    void controllersMustNotDeclareTransactionalMethods() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms.web");

        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.isTopLevelClass() || !javaClass.getSimpleName().endsWith("Controller")) {
                continue;
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getOwner().equals(javaClass)) {
                    continue;
                }
                if (method.isAnnotatedWith(Transactional.class)) {
                    violations.add(javaClass.getSimpleName() + "#" + method.getName());
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Controllers must not declare @Transactional methods: " + violations);
        }
    }

    @Test
    void controllersMustNotDependOnRepositories() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms.web");

        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.isTopLevelClass() || !javaClass.getSimpleName().endsWith("Controller")) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName();
                if (targetPackage.startsWith("com.bhawana.lms.repo")) {
                    violations.add(javaClass.getSimpleName() + " -> " + dependency.getTargetClass().getName());
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Controllers must not depend on repositories: " + violations);
        }
    }
}
