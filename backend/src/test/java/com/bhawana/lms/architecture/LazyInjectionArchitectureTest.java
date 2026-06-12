package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Lazy;

/**
 * B7 guardrail: {@code @Lazy} constructor injection is legacy debt, not an approved pattern.
 * Only the two documented cycles below may remain until B2/B3 dissolve them.
 */
class LazyInjectionArchitectureTest {

    private static final Set<String> ALLOWED_LAZY_CONSTRUCTOR_OWNERS = Set.of(
            "com.bhawana.lms.service.LoanApplicationLifecycleService",
            "com.bhawana.lms.service.LoanApplicationService"
    );

    @Test
    void onlyDocumentedClassesMayUseLazyConstructorInjection() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        List<String> violations = new ArrayList<>();
        int lazyOwnerCount = 0;
        for (JavaClass javaClass : classes) {
            if (!javaClass.isTopLevelClass()) {
                continue;
            }
            boolean ownerHasLazyParameter = false;
            for (JavaConstructor constructor : javaClass.getConstructors()) {
                for (JavaParameter parameter : constructor.getParameters()) {
                    if (!parameter.isAnnotatedWith(Lazy.class)) {
                        continue;
                    }
                    ownerHasLazyParameter = true;
                    if (!ALLOWED_LAZY_CONSTRUCTOR_OWNERS.contains(javaClass.getName())) {
                        violations.add(javaClass.getSimpleName() + " constructor parameter "
                                + parameter.getDescription());
                    }
                }
            }
            if (ownerHasLazyParameter) {
                lazyOwnerCount++;
            }
        }

        if (!violations.isEmpty()) {
            fail("Unexpected @Lazy constructor injection (allowed only on "
                    + ALLOWED_LAZY_CONSTRUCTOR_OWNERS + "): " + violations);
        }
        assertEquals(ALLOWED_LAZY_CONSTRUCTOR_OWNERS.size(), lazyOwnerCount);
    }
}
