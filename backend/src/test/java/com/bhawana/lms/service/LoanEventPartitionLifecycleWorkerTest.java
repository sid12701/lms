package com.bhawana.lms.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bhawana.lms.repo.LoanEventPartitionRepository;
import com.bhawana.lms.repo.LoanEventPartitionRepository.PartitionChange;
import com.bhawana.lms.repo.LoanEventPartitionRepository.PartitionChange.Action;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanEventPartitionLifecycleWorkerTest {

    @Mock private LoanEventPartitionRepository loanEventPartitionRepository;

    @Test
    void scheduledRunAppliesTheConfiguredLeadAndRetention() {
        LoanEventLogProperties properties = new LoanEventLogProperties();
        properties.setPartitionLeadMonths(4);
        properties.setRetentionDays(30);
        when(loanEventPartitionRepository.maintain(4, 30))
                .thenReturn(List.of(new PartitionChange(Action.CREATED, "loan_event_2026_12")));

        new LoanEventPartitionLifecycleWorker(loanEventPartitionRepository, properties).maintainPartitions();

        verify(loanEventPartitionRepository).maintain(4, 30);
    }

    @Test
    void disabledMaintenanceLeavesThePartitionSetAlone() {
        LoanEventLogProperties properties = new LoanEventLogProperties();
        properties.setPartitionMaintenanceEnabled(false);

        new LoanEventPartitionLifecycleWorker(loanEventPartitionRepository, properties).maintainPartitions();

        verifyNoInteractions(loanEventPartitionRepository);
    }

    @Test
    void defaultsMatchTheRetentionWindowTheAdrPublishes() {
        LoanEventLogProperties properties = new LoanEventLogProperties();
        when(loanEventPartitionRepository.maintain(anyInt(), anyInt())).thenReturn(List.of());

        new LoanEventPartitionLifecycleWorker(loanEventPartitionRepository, properties).maintainPartitions();

        verify(loanEventPartitionRepository).maintain(3, 30);
    }
}
