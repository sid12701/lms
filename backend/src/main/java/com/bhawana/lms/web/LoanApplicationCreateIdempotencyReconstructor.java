package com.bhawana.lms.web;

import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.service.IdempotencyResultReconstructor;
import com.bhawana.lms.service.LoanApplicationDetailAssembler;
import com.bhawana.lms.web.LspLoanApplicationApiController.LspLoanApplicationDetailResponse;
import com.bhawana.lms.web.LspLoanApplicationApiController.LspLoanApplicationRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class LoanApplicationCreateIdempotencyReconstructor implements IdempotencyResultReconstructor {

    static final String OPERATION_KEY = "LOAN_APPLICATION_CREATE";

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationDetailAssembler loanApplicationDetailAssembler;

    LoanApplicationCreateIdempotencyReconstructor(
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationDetailAssembler loanApplicationDetailAssembler
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationDetailAssembler = loanApplicationDetailAssembler;
    }

    @Override
    public boolean supports(String operationKey, Class<?> responseType) {
        return OPERATION_KEY.equals(operationKey)
                && LspLoanApplicationDetailResponse.class.isAssignableFrom(responseType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> tryRecover(
            UUID lspId,
            String operationKey,
            Object requestFingerprintSource,
            Class<T> responseType
    ) {
        if (!supports(operationKey, responseType)) {
            return Optional.empty();
        }
        if (!(requestFingerprintSource instanceof LspLoanApplicationRequest request)) {
            return Optional.empty();
        }
        if (lspId == null || !lspId.equals(request.lspId())) {
            return Optional.empty();
        }

        return loanApplicationRepository
                .findDetailedByLsp_IdAndExternalLoanIdIgnoreCase(lspId, request.lspLoanId())
                .map(application -> (T) LspLoanApplicationResponses.toDetailResponse(
                        loanApplicationDetailAssembler.getDetail(application.getId())
                ));
    }
}
