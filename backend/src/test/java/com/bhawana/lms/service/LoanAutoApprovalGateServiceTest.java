package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanApplicationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanAutoApprovalGateServiceTest {

    @Mock
    private LoanApplicationLifecycleService loanApplicationLifecycleService;

    @Mock
    private LoanApplicationRepository loanApplicationRepository;

    private SimpleMeterRegistry meterRegistry;
    private LoanAutoApprovalGateService gateService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        gateService = new LoanAutoApprovalGateService(
                loanApplicationLifecycleService,
                loanApplicationRepository,
                meterRegistry
        );
    }

    @Test
    void skipsWhenChecklistNotJustCompleted() {
        UUID applicationId = UUID.randomUUID();

        gateService.maybeTriggerAutoApproval(applicationId, "lsp.api", false);

        verify(loanApplicationLifecycleService, never()).autoApproveIfEligibleForLsp(eq(applicationId), eq("lsp.api"));
        assertEquals(1.0, meterRegistry.get("lms.auto_approval.gate").tag("outcome", "skipped_incomplete").counter().count());
    }

    @Test
    void skipsWhenApplicationPastIntake() {
        UUID applicationId = UUID.randomUUID();
        LoanApplication application = org.mockito.Mockito.mock(LoanApplication.class);
        when(application.getStatus()).thenReturn(LoanApplicationStatus.REJECTED);
        when(loanApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));

        gateService.maybeTriggerAutoApproval(applicationId, "lsp.api", true);

        verify(loanApplicationLifecycleService, never()).autoApproveIfEligibleForLsp(eq(applicationId), eq("lsp.api"));
        assertEquals(1.0, meterRegistry.get("lms.auto_approval.gate").tag("outcome", "skipped_status").counter().count());
    }

    @Test
    void firesAutoApprovalWhenDocumentsJustCompletedAndStatusAllows() {
        UUID applicationId = UUID.randomUUID();
        LoanApplication application = org.mockito.Mockito.mock(LoanApplication.class);
        when(application.getStatus()).thenReturn(LoanApplicationStatus.INITIALIZED);
        when(loanApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));

        gateService.maybeTriggerAutoApproval(applicationId, "lsp.api", true);

        verify(loanApplicationLifecycleService).autoApproveIfEligibleForLsp(applicationId, "lsp.api");
        assertEquals(1.0, meterRegistry.get("lms.auto_approval.gate").tag("outcome", "fired").counter().count());
    }
}
