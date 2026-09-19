package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanDocumentObject;
import com.bhawana.lms.repo.LoanDocumentObjectRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes document objects the LMS wrote but never linked to committed metadata (M04).
 *
 * <p>Only rows in {@code loan_document_object} are candidates, so objects written before V131
 * — which have no row — are never touched. A candidate must be PENDING and untouched for the
 * grace period, and the claim statement rechecks in the same statement that no version or
 * checklist row references its key. Each step is its own short transaction and the storage
 * delete runs outside any of them (H24): claim (PENDING → DELETING), delete the object, then
 * DELETING → DELETED. A crash between steps leaves DELETING, which the next run finishes; the
 * delete itself is idempotent. Concurrent instances serialize on the row.</p>
 */
@Component
public class LoanDocumentOrphanReconciler {

    private static final Logger log = LoggerFactory.getLogger(LoanDocumentOrphanReconciler.class);

    private final LoanDocumentObjectRepository documentObjectRepository;
    private final LoanDocumentStorageService loanDocumentStorageService;
    private final DocumentStorageProperties.OrphanReconciler properties;
    private final TransactionTemplate transactionTemplate;

    public LoanDocumentOrphanReconciler(
            LoanDocumentObjectRepository documentObjectRepository,
            LoanDocumentStorageService loanDocumentStorageService,
            DocumentStorageProperties documentStorageProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.documentObjectRepository = documentObjectRepository;
        this.loanDocumentStorageService = loanDocumentStorageService;
        this.properties = documentStorageProperties.getOrphanReconciler();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.storage.documents.orphan-reconciler.fixed-delay-ms:3600000}")
    public void reconcileOnSchedule() {
        if (!properties.isEnabled()) {
            return;
        }
        TenantScopedExecution.runAsAdmin(() -> reconcile(Instant.now(), properties.isDryRun()));
    }

    /** One bounded pass. Must run in admin scope. */
    public Result reconcile(Instant now, boolean dryRun) {
        Instant cutoff = now.minus(properties.getGracePeriod());
        List<String> candidates = transactionTemplate.execute(status ->
                documentObjectRepository.findReconcileCandidates(cutoff, properties.getBatchSize()));
        int deleted = 0;
        int relinked = 0;
        int skipped = 0;
        for (String storageKey : candidates) {
            if (dryRun) {
                log.info("document_orphan_candidate dryRun=true storageKey={}", storageKey);
                continue;
            }
            Step step = transactionTemplate.execute(status -> claim(storageKey, cutoff));
            switch (step) {
                case DELETE -> {
                    loanDocumentStorageService.delete(storageKey);
                    transactionTemplate.execute(status -> documentObjectRepository.markDeleted(storageKey));
                    log.info("document_orphan_deleted storageKey={}", storageKey);
                    deleted++;
                }
                case RELINKED -> {
                    log.warn("document_object_relinked storageKey={} reason=referenced_while_pending", storageKey);
                    relinked++;
                }
                case SKIP -> skipped++;
            }
        }
        Result result = new Result(candidates.size(), deleted, relinked, skipped, dryRun);
        if (!candidates.isEmpty()) {
            log.info(
                    "document_orphan_reconcile candidates={} deleted={} relinked={} skipped={} dryRun={}",
                    result.candidates(), deleted, relinked, skipped, dryRun
            );
        }
        return result;
    }

    private Step claim(String storageKey, Instant cutoff) {
        if (LoanDocumentObject.DELETING.equals(documentObjectRepository.findState(storageKey))) {
            return Step.DELETE; // an earlier pass claimed it and stopped before finishing
        }
        if (documentObjectRepository.claimForDeletion(storageKey, cutoff) == 1) {
            return Step.DELETE;
        }
        return documentObjectRepository.relinkIfReferenced(storageKey) == 1 ? Step.RELINKED : Step.SKIP;
    }

    private enum Step {
        DELETE,
        RELINKED,
        SKIP
    }

    public record Result(int candidates, int deleted, int relinked, int skipped, boolean dryRun) {
    }
}
