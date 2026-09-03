package com.bhawana.lms.service;

import com.bhawana.lms.repo.LoanEventPartitionRepository;
import com.bhawana.lms.repo.LoanEventPartitionRepository.PartitionChange;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the loan event log partitioned ahead of need and pruned to its retention window (ADR 0007).
 *
 * <p>Runs on every pod on a fixed delay, starting as soon as the scheduler does. Maintenance
 * serialises and is idempotent inside Postgres, so concurrent pods cost a short wait and nothing
 * else, and a run with nothing to do is silent.
 */
@Component
public class LoanEventPartitionLifecycleWorker {

    private static final Logger log = LoggerFactory.getLogger(LoanEventPartitionLifecycleWorker.class);

    private final LoanEventPartitionRepository loanEventPartitionRepository;
    private final LoanEventLogProperties properties;

    public LoanEventPartitionLifecycleWorker(
            LoanEventPartitionRepository loanEventPartitionRepository,
            LoanEventLogProperties properties
    ) {
        this.loanEventPartitionRepository = loanEventPartitionRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.loan-event-log.partition-maintenance-fixed-delay-ms:3600000}")
    public void maintainPartitions() {
        if (!properties.isPartitionMaintenanceEnabled()) {
            return;
        }
        TenantScopedExecution.runAsAdmin(this::maintainPartitionsUnderAdminScope);
    }

    void maintainPartitionsUnderAdminScope() {
        List<PartitionChange> changes = loanEventPartitionRepository.maintain(
                properties.getPartitionLeadMonths(),
                properties.getRetentionDays()
        );
        if (changes.isEmpty()) {
            return;
        }
        log.info(
                "loan_event_partitions_maintained created={} dropped={} leadMonths={} retentionDays={}",
                partitionNames(changes, PartitionChange.Action.CREATED),
                partitionNames(changes, PartitionChange.Action.DROPPED),
                properties.getPartitionLeadMonths(),
                properties.getRetentionDays()
        );
    }

    private static List<String> partitionNames(List<PartitionChange> changes, PartitionChange.Action action) {
        return changes.stream()
                .filter(change -> change.action() == action)
                .map(PartitionChange::partitionName)
                .toList();
    }
}
