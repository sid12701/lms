package com.bhawana.lms.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H12 guardrail: every scheduled job declares how it survives a second instance.
 *
 * <p>Scheduled work runs on every deployed instance at once. A job with no concurrency strategy
 * double-disburses, double-alerts or double-purges the moment the service scales past one pod —
 * and it passes every test on a single-instance developer machine.
 *
 * <p>There are two accepted strategies here. Either the job takes a PostgreSQL advisory lock via
 * {@code PostgresAdvisoryLockSupport}, or it claims its own rows (for example {@code SELECT …
 * FOR UPDATE SKIP LOCKED}) so that two instances cannot pick up the same work. A new
 * {@code @Scheduled} method that does neither fails this rule and has to state which one it is.
 *
 * <p>The allowlist records the row-claiming jobs and why each is safe. It is deliberately a
 * written justification per class, not a package exemption.
 */
class ScheduledJobConcurrencyArchitectureTest {

    private static final String ADVISORY_LOCK_SUPPORT =
            "com.bhawana.lms.service.PostgresAdvisoryLockSupport";

    /** Jobs that are safe without an advisory lock, with the mechanism that makes them safe. */
    private static final Map<String, String> ROW_CLAIM_ALLOWLIST = new LinkedHashMap<>(Map.of(
            "com.bhawana.lms.service.LoanDisbursementWorker",
            "Claims disbursement rows under lock before acting; a losing instance sees no rows.",
            "com.bhawana.lms.service.ReportRequestProcessingWorker",
            "claimBatchForProcessing claims PENDING rows, so a second instance claims a disjoint batch.",
            "com.bhawana.lms.service.IdempotencyRecordRetentionWorker",
            "Purge is an idempotent delete by expiry; a duplicate run removes nothing extra.",
            "com.bhawana.lms.service.LoanEventPartitionLifecycleWorker",
            "Partition maintenance uses IF NOT EXISTS / IF EXISTS DDL and is idempotent."
    ));

    @Test
    void everyScheduledJobHasADeclaredConcurrencyStrategy() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            if (!hasScheduledMethod(javaClass)) {
                continue;
            }
            if (ROW_CLAIM_ALLOWLIST.containsKey(javaClass.getName())) {
                continue;
            }
            if (!dependsOnAdvisoryLockSupport(javaClass)) {
                violations.add(javaClass.getName());
            }
        }

        if (!violations.isEmpty()) {
            fail("""
                    Scheduled job with no declared concurrency strategy (H12). Either inject \
                    PostgresAdvisoryLockSupport and guard the run, or claim rows so a second \
                    instance cannot pick up the same work and add the class to \
                    ROW_CLAIM_ALLOWLIST with the reason. Violations: """ + violations);
        }
    }

    @Test
    void allowlistedJobsStillExistAndAreStillScheduled() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bhawana.lms");

        List<String> stale = new ArrayList<>();
        for (String allowlisted : ROW_CLAIM_ALLOWLIST.keySet()) {
            boolean stillScheduled = classes.stream()
                    .anyMatch(candidate -> candidate.getName().equals(allowlisted)
                            && hasScheduledMethod(candidate));
            if (!stillScheduled) {
                stale.add(allowlisted);
            }
        }

        if (!stale.isEmpty()) {
            fail("ROW_CLAIM_ALLOWLIST has entries that are no longer scheduled jobs; "
                    + "remove them so the list keeps meaning something: " + stale);
        }
    }

    private static boolean hasScheduledMethod(JavaClass javaClass) {
        for (JavaMethod method : javaClass.getMethods()) {
            if (!method.getOwner().equals(javaClass)) {
                continue;
            }
            boolean scheduled = method.getAnnotations().stream()
                    .anyMatch(annotation -> annotation.getRawType().getName()
                            .equals("org.springframework.scheduling.annotation.Scheduled"));
            if (scheduled) {
                return true;
            }
        }
        return false;
    }

    private static boolean dependsOnAdvisoryLockSupport(JavaClass javaClass) {
        for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
            if (ADVISORY_LOCK_SUPPORT.equals(dependency.getTargetClass().getName())) {
                return true;
            }
        }
        return false;
    }
}
