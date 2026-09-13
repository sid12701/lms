package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H04 guardrail: predictable randomness never reaches production code.
 *
 * <p>H04 found browser {@code Math.random()} minting temporary user passwords. The Java
 * equivalents — {@code java.util.Random} and {@code ThreadLocalRandom} — are seeded from
 * predictable state and are equally unfit for passwords, secrets, tokens and idempotency keys.
 * Every credential path in this codebase uses {@link java.security.SecureRandom}; this rule
 * keeps it that way.
 *
 * <p>The ban is codebase-wide rather than scoped to security packages on purpose. A helper
 * written in {@code service} with a weak generator gets reused by a credential path later, and
 * the review that would have caught it never happens twice.
 */
class CredentialRandomnessArchitectureTest {

    /**
     * Non-credential randomness, with the reason each use is not security-relevant.
     *
     * <p>An entry here is a claim that nothing a caller must not guess is derived from this
     * generator. Adding one is a security judgement, so it gets written down.
     */
    private static final Map<String, String> NON_CREDENTIAL_ALLOWLIST = new LinkedHashMap<>(Map.of(
            "com.bhawana.lms.service.MockLoanDisbursementAdapter",
            "Fabricates a bank RRN for the in-process payment stand-in; never a secret or token."
    ));

    @Test
    void productionCodeUsesOnlyCryptographicRandomness() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            if (NON_CREDENTIAL_ALLOWLIST.containsKey(javaClass.getName())) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if ("java.util.Random".equals(target)
                        || "java.util.concurrent.ThreadLocalRandom".equals(target)) {
                    violations.add(javaClass.getName() + " -> " + target);
                }
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getOwner().equals(javaClass)) {
                    continue;
                }
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if ("random".equals(call.getName())
                            && "java.lang.Math".equals(call.getTargetOwner().getName())) {
                        violations.add(javaClass.getName() + "#" + method.getName() + " -> Math.random()");
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("""
                    Predictable randomness in production code (H04). Use java.security.SecureRandom \
                    for anything a caller must not be able to guess — passwords, client secrets, \
                    tokens, keys. Violations: """ + violations);
        }
    }
}
