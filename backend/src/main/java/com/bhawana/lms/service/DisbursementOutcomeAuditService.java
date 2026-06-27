package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementOutcomeAudit;
import com.bhawana.lms.domain.DisbursementOutcomeAuditOutcome;
import com.bhawana.lms.domain.DisbursementOutcomeAuditSource;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.repo.DisbursementOutcomeAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisbursementOutcomeAuditService {

    private final DisbursementOutcomeAuditRepository repository;

    public DisbursementOutcomeAuditService(DisbursementOutcomeAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void recordOutcomeApplied(
            LoanApplication loanApplication,
            LoanAccount loanAccount,
            String actorUsername,
            String actorIp,
            String correlationId,
            DisbursementOutcomeAuditOutcome outcome,
            String providerRequestId
    ) {
        repository.save(new DisbursementOutcomeAudit(
                loanApplication,
                loanAccount,
                actorUsername,
                actorIp,
                correlationId,
                DisbursementOutcomeAuditSource.MOCK_OUTCOME_ENDPOINT,
                outcome,
                providerRequestId
        ));
    }
}
