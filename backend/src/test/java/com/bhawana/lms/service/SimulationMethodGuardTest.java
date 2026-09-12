package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mock-outcome and auto-resolve simulation boundary lives on the command service
 * methods themselves, independent of which {@link LoanDisbursementAdapter} is wired, so a
 * future real bank adapter cannot silently re-enable simulation paths. No real adapter is
 * fabricated here: the denial is proven with the mock adapter type still wired.
 */
@ExtendWith(MockitoExtension.class)
class SimulationMethodGuardTest {

    @Mock private LoanApplicationRepository loanApplicationRepository;
    @Mock private LoanAccountRepository loanAccountRepository;
    @Mock private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Mock private DisbursementIntentRepository disbursementIntentRepository;
    @Mock private LoanDisbursementAdapter loanDisbursementAdapter;
    @Mock private LoanApplicationQueryService loanApplicationQueryService;
    @Mock private LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService;
    @Mock private LoanApplicationStatusWriter loanApplicationStatusWriter;
    @Mock private DisbursementOutcomeApplier disbursementOutcomeApplier;
    @Mock private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private LoanApplication application;
    @Mock private LoanAccount loanAccount;

    private final UUID applicationId = UUID.randomUUID();

    @Test
    void mockOutcomeIsDeniedOutsideSimulationProfiles() {
        LoanDisbursementCommandService service = serviceWithProfiles("prod");

        BusinessRuleViolationException failure = assertThrows(BusinessRuleViolationException.class,
                () -> service.resolveMockDisbursementOutcome(
                        applicationId, "ops.admin", null, "t01", MockDisbursementOutcome.DISBURSED));
        assertEquals(DisbursementSimulationGuard.SIMULATION_NOT_ALLOWED, failure.getErrorCode());
        verifyNoInteractions(
                loanApplicationQueryService, loanAccountRepository, loanDisbursementRequestLogRepository,
                disbursementOutcomeApplier, loanDisbursementAdapter);
    }

    @Test
    void autoResolveIsDeniedOutsideSimulationProfiles() {
        LoanDisbursementCommandService service = serviceWithProfiles();

        BusinessRuleViolationException failure = assertThrows(BusinessRuleViolationException.class,
                () -> service.autoResolveAfterInitiate(applicationId, "worker", null, "t01"));
        assertEquals(DisbursementSimulationGuard.SIMULATION_NOT_ALLOWED, failure.getErrorCode());
        verifyNoInteractions(
                loanApplicationQueryService, loanAccountRepository, loanDisbursementRequestLogRepository,
                disbursementOutcomeApplier, loanDisbursementAdapter);
    }

    @Test
    void autoResolveStillRunsUnderExplicitSimulationProfile() {
        LoanDisbursementCommandService service = serviceWithProfiles("test");
        when(loanApplicationQueryService.getApplication(applicationId)).thenReturn(application);
        when(loanAccountRepository.findDetailedByLoanApplication_Id(applicationId))
                .thenReturn(Optional.of(loanAccount));
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.PENDING_DISBURSEMENT);

        assertNull(service.autoResolveAfterInitiate(applicationId, "worker", null, "t01"));
        verifyNoInteractions(disbursementOutcomeApplier, loanDisbursementAdapter);
    }

    private LoanDisbursementCommandService serviceWithProfiles(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setActiveProfiles(activeProfiles);
        }
        LoanDisbursementMockProperties mockProperties = new LoanDisbursementMockProperties();
        return new LoanDisbursementCommandService(
                loanApplicationRepository,
                loanAccountRepository,
                loanDisbursementRequestLogRepository,
                loanDisbursementAdapter,
                loanApplicationQueryService,
                loanApplicationDocumentChecklistService,
                loanApplicationStatusWriter,
                disbursementOutcomeApplier,
                mockProperties,
                disbursementIntentWorkflowService,
                new DisbursementPaymentModeSelector(mockProperties),
                new DisbursementSimulationGuard(environment),
                org.mockito.Mockito.mock(DisbursementObservationWriter.class),
                org.mockito.Mockito.mock(com.bhawana.lms.repo.DisbursementIntentRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(DisbursementProviderLatency.class),
                transactionTemplate,
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class)
        );
    }
}
