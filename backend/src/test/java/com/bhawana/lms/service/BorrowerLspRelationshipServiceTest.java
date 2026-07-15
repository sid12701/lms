package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerLspRelationship;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerLspRelationshipRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class BorrowerLspRelationshipServiceTest {

    @Autowired
    private BorrowerLspRelationshipService borrowerLspRelationshipService;

    @Autowired
    private BorrowerLspRelationshipRepository borrowerLspRelationshipRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void clean() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void grantVisibilityDualWritesAccessCollectionAndRelationship() {
        Lsp lsp = TenantScopedExecution.callAsAdmin(() -> lspRepository.save(new Lsp("REL-A", "Rel Lsp A", LspStatus.ACTIVE)));
        Borrower borrower = TenantScopedExecution.callAsAdmin(() -> {
            Borrower created = new Borrower(BorrowerProfile.minimal(
                    "Rel Borrower", "RELPA1234A", "9000000001", "rel@example.com"));
            return borrowerLspRelationshipService.grantVisibility(
                    created,
                    lsp,
                    BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING
            );
        });

        assertTrue(borrower.hasVisibilityFor(lsp.getId()));
        BorrowerLspRelationship relationship = TenantScopedExecution.callAsAdmin(() ->
                borrowerLspRelationshipRepository.findByBorrower_IdAndLsp_Id(borrower.getId(), lsp.getId()).orElseThrow());
        assertEquals(BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING, relationship.getSourceChannel());
        assertNotNull(relationship.getFirstSourcedAt());
        assertEquals(1L, TenantScopedExecution.callAsAdmin(() ->
                borrowerLspRelationshipRepository.countByBorrower_Id(borrower.getId())));
    }

    @Test
    void grantVisibilityKeepsAccessCollectionAndRelationshipInParity() {
        Lsp apex = TenantScopedExecution.callAsAdmin(() ->
                lspRepository.save(new Lsp("REL-C", "Rel Lsp C", LspStatus.ACTIVE)));
        Lsp north = TenantScopedExecution.callAsAdmin(() ->
                lspRepository.save(new Lsp("REL-D", "Rel Lsp D", LspStatus.ACTIVE)));

        Borrower borrower = TenantScopedExecution.callAsAdmin(() -> {
            Borrower created = new Borrower(BorrowerProfile.minimal(
                    "Parity Borrower", "RELPC1234C", "9000000003", "relc@example.com"));
            created = borrowerLspRelationshipService.grantVisibility(
                    created, apex, BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING);
            return borrowerLspRelationshipService.grantVisibility(
                    created, north, BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING);
        });

        assertEquals(2, borrower.getVisibleLspIds().size());
        assertTrue(borrower.hasVisibilityFor(apex.getId()));
        assertTrue(borrower.hasVisibilityFor(north.getId()));
        assertEquals(2L, TenantScopedExecution.callAsAdmin(() ->
                borrowerLspRelationshipRepository.countByBorrower_Id(borrower.getId())));

        Borrower reloaded = TenantScopedExecution.callAsAdmin(() ->
                borrowerRepository.findById(borrower.getId()).orElseThrow());
        // Must not warn — access and relationship sets match.
        borrowerLspRelationshipService.assertVisibilityParity(reloaded);
    }

    @Test
    void regrantTouchesExistingRelationshipWithoutDuplicating() throws InterruptedException {
        Lsp lsp = TenantScopedExecution.callAsAdmin(() -> lspRepository.save(new Lsp("REL-B", "Rel Lsp B", LspStatus.ACTIVE)));
        Borrower borrower = TenantScopedExecution.callAsAdmin(() -> {
            Borrower created = new Borrower(BorrowerProfile.minimal(
                    "Rel Borrower B", "RELPB1234B", "9000000002", "relb@example.com"));
            return borrowerLspRelationshipService.grantVisibility(
                    created,
                    lsp,
                    BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING
            );
        });

        BorrowerLspRelationship first = TenantScopedExecution.callAsAdmin(() ->
                borrowerLspRelationshipRepository.findByBorrower_IdAndLsp_Id(borrower.getId(), lsp.getId()).orElseThrow());
        UUID firstId = first.getId();
        var firstTouched = first.getLastTouchedAt();

        Thread.sleep(5);

        TenantScopedExecution.runAsAdmin(() -> {
            Borrower reloaded = borrowerRepository.findById(borrower.getId()).orElseThrow();
            borrowerLspRelationshipService.grantVisibility(
                    reloaded,
                    lsp,
                    BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING
            );
        });

        BorrowerLspRelationship second = TenantScopedExecution.callAsAdmin(() ->
                borrowerLspRelationshipRepository.findByBorrower_IdAndLsp_Id(borrower.getId(), lsp.getId()).orElseThrow());
        assertEquals(firstId, second.getId());
        assertTrue(second.getLastTouchedAt().compareTo(firstTouched) >= 0);
        assertEquals(1L, TenantScopedExecution.callAsAdmin(() ->
                borrowerLspRelationshipRepository.countByBorrower_Id(borrower.getId())));
    }

    @Test
    void borrowerHasNoPublicGrantVisibilityApi() throws Exception {
        assertTrue(
                java.util.Arrays.stream(Borrower.class.getMethods())
                        .noneMatch(method -> method.getName().startsWith("grantVisibility")),
                "Borrower must not expose a public grantVisibility* API that skips Spec S19 dual-write"
        );
        assertTrue(
                java.lang.reflect.Modifier.isPublic(
                        Borrower.class.getDeclaredMethod("addVisibleLspId", UUID.class).getModifiers()
                ) == false,
                "addVisibleLspId must stay package-private"
        );
        assertTrue(
                java.util.Arrays.stream(BorrowerLspRelationshipService.class.getMethods())
                        .noneMatch(method -> method.getName().equals("upsertRelationship")),
                "Relationship upsert must not be public because it can bypass the legacy visibility dual-write"
        );
    }
}
