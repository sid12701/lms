package com.bhawana.lms.service;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerLegacyAccessWriter;
import com.bhawana.lms.domain.BorrowerLspRelationship;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.BorrowerLspRelationshipRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dual-writes Spec S19 relationship rows alongside the legacy
 * {@code borrower.visibleLspIds} element collection.
 */
@Service
public class BorrowerLspRelationshipService {

    private static final Logger log = LoggerFactory.getLogger(BorrowerLspRelationshipService.class);

    private final BorrowerRepository borrowerRepository;
    private final BorrowerLspRelationshipRepository borrowerLspRelationshipRepository;
    private final BorrowerLegacyAccessWriter borrowerLegacyAccessWriter;

    public BorrowerLspRelationshipService(
            BorrowerRepository borrowerRepository,
            BorrowerLspRelationshipRepository borrowerLspRelationshipRepository,
            BorrowerLegacyAccessWriter borrowerLegacyAccessWriter
    ) {
        this.borrowerRepository = borrowerRepository;
        this.borrowerLspRelationshipRepository = borrowerLspRelationshipRepository;
        this.borrowerLegacyAccessWriter = borrowerLegacyAccessWriter;
    }

    /**
     * Grants legacy collection visibility, persists the borrower, then upserts
     * the relationship row (borrower must exist before the FK insert).
     * This is the only supported way to grant LSP visibility.
     */
    @Transactional
    public Borrower grantVisibility(Borrower borrower, Lsp lsp, String sourceChannel) {
        Objects.requireNonNull(borrower, "borrower");
        Objects.requireNonNull(lsp, "lsp");
        borrowerLegacyAccessWriter.addVisibleLspId(borrower, lsp.getId());
        Borrower saved = borrowerRepository.save(borrower);
        upsertRelationship(saved, lsp, sourceChannel);
        return saved;
    }

    private BorrowerLspRelationship upsertRelationship(Borrower borrower, Lsp lsp, String sourceChannel) {
        Objects.requireNonNull(borrower, "borrower");
        Objects.requireNonNull(lsp, "lsp");
        return borrowerLspRelationshipRepository.findByBorrower_IdAndLsp_Id(borrower.getId(), lsp.getId())
                .map(existing -> {
                    existing.touch();
                    return borrowerLspRelationshipRepository.save(existing);
                })
                .orElseGet(() -> borrowerLspRelationshipRepository.save(
                        new BorrowerLspRelationship(borrower, lsp, sourceChannel)
                ));
    }

    @Transactional(readOnly = true)
    public List<BorrowerLspRelationship> listForBorrower(UUID borrowerId) {
        return borrowerLspRelationshipRepository.findByBorrower_IdOrderByFirstSourcedAtAsc(borrowerId);
    }

    /**
     * Logs (does not fail) when access-collection ids diverge from relationship rows.
     * Used as dual-read verification until {@code borrower_lsp_access} is dropped.
     */
    @Transactional(readOnly = true)
    public void assertVisibilityParity(Borrower borrower) {
        Set<UUID> accessIds = borrower.getVisibleLspIds();
        Set<UUID> relationshipIds = borrowerLspRelationshipRepository
                .findByBorrower_IdOrderByFirstSourcedAtAsc(borrower.getId())
                .stream()
                .map(rel -> rel.getLsp().getId())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!accessIds.equals(relationshipIds)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("borrowerId", borrower.getId());
            details.put("accessLspIds", accessIds);
            details.put("relationshipLspIds", relationshipIds);
            log.warn("borrower_lsp_visibility_parity_divergence details={}", details);
        }
    }
}
