package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * H24 guardrail: a database transaction is not an external transaction (audit contract C).
 *
 * <p>A {@code @Transactional} method holds a pooled connection for its whole duration. When that
 * duration includes an object-storage PUT or an SMTP send, one slow third party holds database
 * connections until the pool is exhausted and unrelated requests start failing. Worse, the
 * external effect cannot be rolled back: the mail goes out, then the transaction aborts, and the
 * database and the outside world disagree permanently.
 *
 * <p>The rule walks the call graph out of every {@code @Transactional} method and fails when it
 * reaches storage or notification. Fixes are structural, not cosmetic: commit the state change,
 * then perform the external effect outside the transaction and record its result in a second
 * short transaction.
 */
class TransactionalExternalEffectArchitectureTest {

    private static final String TRANSACTIONAL =
            "org.springframework.transaction.annotation.Transactional";

    /** Types whose methods leave the process: object storage, mail, raw SDK clients. */
    private static final Set<String> EXTERNAL_EFFECT_TYPES = Set.of(
            "com.bhawana.lms.service.ReportStorageService",
            "com.bhawana.lms.service.LoanDocumentStorageService",
            "com.bhawana.lms.service.ReportNotificationService",
            "software.amazon.awssdk.services.s3.S3Client",
            "org.springframework.mail.JavaMailSender"
    );

    /**
     * Known-open debt, carried explicitly rather than hidden by weakening the rule.
     *
     * <p>Each entry names the finding it belongs to. Removing an entry must mean the transaction
     * boundary was actually moved — never that the rule became inconvenient.
     */
    private static final Map<String, String> OPEN_DEBT_ALLOWLIST = new LinkedHashMap<>(Map.of(
            "com.bhawana.lms.service.LoanApplicationServicingReadService#downloadDocumentZip",
            "H24-class (open, found by this rule): read-write transaction held across N document "
                    + "fetches while building a ZIP. Not in the original audit.",
            "com.bhawana.lms.service.ReportRequestService#getCompletedReport",
            "Lower severity: readOnly transaction held across one report fetch. No rollback "
                    + "divergence, but it still occupies a pooled connection for the download.",
            "com.bhawana.lms.service.ReportRequestService#getCompletedReportDownload",
            "Lower severity: readOnly transaction held across one report fetch, as above."
    ));

    @Test
    void transactionsDoNotSpanExternalEffects() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        Map<String, List<JavaMethod>> methodIndex = indexMethodsByOwnerAndName(classes);
        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getOwner().equals(javaClass) || !isTransactional(method)) {
                    continue;
                }
                String methodKey = javaClass.getName() + "#" + method.getName();
                if (OPEN_DEBT_ALLOWLIST.containsKey(methodKey)) {
                    continue;
                }
                String reached = findExternalEffectReachableFrom(method, methodIndex);
                if (reached != null) {
                    violations.add(methodKey + " reaches " + reached);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("""
                    @Transactional method spans an external effect (H24). Commit the state change, \
                    perform the storage/mail call outside the transaction, then record its outcome \
                    in a second short transaction. Violations: """ + violations);
        }
    }

    @Test
    void openDebtAllowlistEntriesStillExist() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        Set<String> transactionalMethodKeys = new HashSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.getOwner().equals(javaClass) && isTransactional(method)) {
                    transactionalMethodKeys.add(javaClass.getName() + "#" + method.getName());
                }
            }
        }

        List<String> stale = OPEN_DEBT_ALLOWLIST.keySet().stream()
                .filter(key -> !transactionalMethodKeys.contains(key))
                .toList();

        if (!stale.isEmpty()) {
            fail("OPEN_DEBT_ALLOWLIST names methods that are no longer transactional. "
                    + "If the boundary was fixed, delete the entry: " + stale);
        }
    }

    private static String findExternalEffectReachableFrom(
            JavaMethod entryPoint,
            Map<String, List<JavaMethod>> methodIndex
    ) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<JavaMethod> pending = new ArrayDeque<>();
        pending.add(entryPoint);
        visited.add(entryPoint.getOwner().getName() + "#" + entryPoint.getName());

        while (!pending.isEmpty()) {
            JavaMethod current = pending.remove();
            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                String ownerName = call.getTargetOwner().getName();
                if (EXTERNAL_EFFECT_TYPES.contains(ownerName)) {
                    return ownerName + "." + call.getName() + "()";
                }
                if (!ownerName.startsWith("com.bhawana.lms")) {
                    continue;
                }
                String key = ownerName + "#" + call.getName();
                if (!visited.add(key)) {
                    continue;
                }
                pending.addAll(methodIndex.getOrDefault(key, List.of()));
            }
        }
        return null;
    }

    private static Map<String, List<JavaMethod>> indexMethodsByOwnerAndName(JavaClasses classes) {
        Map<String, List<JavaMethod>> index = new LinkedHashMap<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.getOwner().equals(javaClass)) {
                    continue;
                }
                index.computeIfAbsent(
                        javaClass.getName() + "#" + method.getName(),
                        ignored -> new ArrayList<>()
                ).add(method);
            }
        }
        return index;
    }

    private static boolean isTransactional(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> TRANSACTIONAL.equals(annotation.getRawType().getName()));
    }
}
