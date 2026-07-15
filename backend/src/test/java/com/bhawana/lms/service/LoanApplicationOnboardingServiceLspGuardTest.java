package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.util.JsonPayloadSerializer;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Loan creation runs admin-scoped (row-level security off), so {@link LoanApplicationOnboardingService}
 * re-asserts tenant ownership in code: an LSP-authenticated create must be written for the LSP the
 * caller authenticated as. These tests pin that guard, which the integration suite only exercises on
 * the matching path (the API controller rejects a mismatched body before the service is reached).
 */
@ExtendWith(MockitoExtension.class)
class LoanApplicationOnboardingServiceLspGuardTest {

    @Mock private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    @Mock private LoanApplicationRepository loanApplicationRepository;
    @Mock private LoanProductRepository loanProductRepository;
    @Mock private LoanProductVersionRepository loanProductVersionRepository;
    @Mock private LspRepository lspRepository;
    @Mock private LoanProductLspMappingRepository loanProductLspMappingRepository;
    @Mock private BorrowerOnboardingService borrowerOnboardingService;
    @Mock private LoanApplicationDocumentChecklistService documentChecklistService;
    @Mock private WebhookOutboxService webhookOutboxService;
    @Mock private AdminScopedTransactionExecutor adminScopedTransactionExecutor;
    @Mock private JsonPayloadSerializer jsonPayloadSerializer;

    private LoanApplicationOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new LoanApplicationOnboardingService(
                loanApplicationIntakeAuditRepository,
                loanApplicationRepository,
                loanProductRepository,
                loanProductVersionRepository,
                lspRepository,
                loanProductLspMappingRepository,
                borrowerOnboardingService,
                documentChecklistService,
                webhookOutboxService,
                adminScopedTransactionExecutor,
                jsonPayloadSerializer
        );
    }

    @Test
    void rejectsCreateAndWritesNothingWhenLspDoesNotMatchAuthenticatedLsp() {
        UUID requestedLspId = UUID.randomUUID();
        UUID authenticatedLspId = UUID.randomUUID(); // a different LSP than the request body

        Lsp lsp = mock(Lsp.class);
        when(lsp.getId()).thenReturn(requestedLspId);
        when(lspRepository.findById(requestedLspId)).thenReturn(Optional.of(lsp));
        runAdminScopeInline();

        LoanApplicationOnboardingCommand command = commandForLsp(requestedLspId);

        assertThatThrownBy(() -> service.createApplication("lsp.client", command, authenticatedLspId))
                .isInstanceOf(AccessDeniedException.class);

        // The guard fires before any write: borrower dedup and loan/audit persistence never run.
        verifyNoInteractions(borrowerOnboardingService);
        verify(loanApplicationRepository, never()).save(any());
        verify(loanApplicationIntakeAuditRepository, never()).save(any());
    }

    @Test
    void doesNotApplyOwnershipGuardForTrustedCallersWithoutEnforcedLsp() {
        UUID requestedLspId = UUID.randomUUID();

        Lsp lsp = mock(Lsp.class); // unstubbed status -> not ACTIVE, so the flow stops just past the guard
        when(lspRepository.findById(requestedLspId)).thenReturn(Optional.of(lsp));
        runAdminScopeInline();

        LoanApplicationOnboardingCommand command = commandForLsp(requestedLspId);

        // enforcedLspId == null (internal ops/admin path): the guard is skipped, so whatever stops the
        // create downstream, it is never the ownership guard.
        assertThatThrownBy(() -> service.createApplication("ops.user", command, null))
                .isNotInstanceOf(AccessDeniedException.class);
    }

    private void runAdminScopeInline() {
        when(adminScopedTransactionExecutor.call(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    private static LoanApplicationOnboardingCommand commandForLsp(UUID lspId) {
        // Only lspId is read before the ownership guard, so the rest can be left null.
        return new LoanApplicationOnboardingCommand(lspId, null, null, null, null, null, null, null, null);
    }
}
