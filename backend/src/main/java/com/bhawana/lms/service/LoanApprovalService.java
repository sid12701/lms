package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplication;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApprovalService {

    private final LoanApplicationLifecycleService loanApplicationLifecycleService;

    public LoanApprovalService(LoanApplicationLifecycleService loanApplicationLifecycleService) {
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
    }

    @Transactional
    public LoanApplication autoApproveIfEligibleForLsp(UUID applicationId, String actorUsername) {
        return loanApplicationLifecycleService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
    }
}
