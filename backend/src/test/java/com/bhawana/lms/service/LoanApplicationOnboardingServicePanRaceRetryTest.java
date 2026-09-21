package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.util.JsonPayloadSerializer;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.ScopePreservingTransactionExecutor;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

/**
 * M02: the onboarding retry loop exists for exactly one recoverable race — a
 * concurrent insert winning {@code uk_borrower_pan} between the find-or-create
 * lookup and the borrower insert. Replaying anything else re-fails on identical
 * input, so a non-PAN integrity violation must propagate on first sight while a
 * PAN violation is retried and resolved through the committed-read reuse path.
 * The violation can surface mid-transaction at flush or wrapped at commit, so
 * both shapes are exercised.
 */
@ExtendWith(MockitoExtension.class)
class LoanApplicationOnboardingServicePanRaceRetryTest {

    @Mock private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    @Mock private LoanApplicationRepository loanApplicationRepository;
    @Mock private LoanProductRepository loanProductRepository;
    @Mock private LoanProductVersionRepository loanProductVersionRepository;
    @Mock private LspRepository lspRepository;
    @Mock private LoanProductLspMappingRepository loanProductLspMappingRepository;
    @Mock private BorrowerOnboardingService borrowerOnboardingService;
    @Mock private LoanApplicationDocumentChecklistService documentChecklistService;
    @Mock private LoanEventLog loanEventLog;
    @Mock private ScopePreservingTransactionExecutor scopePreservingTransactionExecutor;
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
                loanEventLog,
                scopePreservingTransactionExecutor,
                jsonPayloadSerializer
        );
    }

    @Test
    void panUniqueViolationIsRetriedAndResolvesOnNextAttempt() {
        LoanApplication committed = mock(LoanApplication.class);
        AtomicInteger attempts = new AtomicInteger();
        when(scopePreservingTransactionExecutor.call(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw panViolation();
            }
            return committed;
        });

        LoanApplication result = service.createApplication("lsp.client", command(), null);

        assertSame(committed, result);
        assertEquals(2, attempts.get());
    }

    @Test
    void commitWrappedPanUniqueViolationIsAlsoRetried() {
        LoanApplication committed = mock(LoanApplication.class);
        AtomicInteger attempts = new AtomicInteger();
        when(scopePreservingTransactionExecutor.call(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new TransactionSystemException("commit failed", panViolation());
            }
            return committed;
        });

        LoanApplication result = service.createApplication("lsp.client", command(), null);

        assertSame(committed, result);
        assertEquals(2, attempts.get());
    }

    @Test
    void nonPanIntegrityViolationIsNotRetried() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "duplicate key",
                new ConstraintViolationException(
                        "duplicate key",
                        new SQLException("duplicate key value violates unique constraint", "23505"),
                        "uk_loan_application_lsp_external"
                )
        );
        when(scopePreservingTransactionExecutor.call(any())).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.createApplication("lsp.client", command(), null)
        );
        assertSame(failure, thrown);
        verify(scopePreservingTransactionExecutor, times(1)).call(any());
    }

    @Test
    void integrityViolationWithoutAConstraintNameIsNotRetried() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("not null");
        when(scopePreservingTransactionExecutor.call(any())).thenThrow(failure);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.createApplication("lsp.client", command(), null)
        );
        verify(scopePreservingTransactionExecutor, times(1)).call(any());
    }

    @Test
    void businessRuleFailuresAreNotRetried() {
        IllegalStateException failure = new IllegalStateException("deterministic failure");
        when(scopePreservingTransactionExecutor.call(any())).thenThrow(failure);

        assertThrows(
                IllegalStateException.class,
                () -> service.createApplication("lsp.client", command(), null)
        );
        verify(scopePreservingTransactionExecutor, times(1)).call(any());
    }

    @Test
    void persistentPanRaceExhaustsTheBoundAndPropagates() {
        AtomicInteger attempts = new AtomicInteger();
        when(scopePreservingTransactionExecutor.call(any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw panViolation();
        });

        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.createApplication("lsp.client", command(), null)
        );
        assertEquals(3, attempts.get());
    }

    private static DataIntegrityViolationException panViolation() {
        return new DataIntegrityViolationException(
                "duplicate key",
                new ConstraintViolationException(
                        "duplicate key",
                        new SQLException("duplicate key value violates unique constraint \"uk_borrower_pan\"", "23505"),
                        "uk_borrower_pan"
                )
        );
    }

    private static LoanApplicationOnboardingCommand command() {
        return new LoanApplicationOnboardingCommand(
                UUID.randomUUID(), null, null, "EXT-1", "API", null, null, null, null);
    }
}
