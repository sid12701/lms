package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisbursementReferenceService {

    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanAccountRepository loanAccountRepository;
    private final DisbursementIntentRepository disbursementIntentRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final ObjectMapper objectMapper;

    public DisbursementReferenceService(
            LoanApplicationQueryService loanApplicationQueryService,
            LoanAccountRepository loanAccountRepository,
            DisbursementIntentRepository disbursementIntentRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            ObjectMapper objectMapper
    ) {
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanAccountRepository = loanAccountRepository;
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<DisbursementReference> resolve(UUID applicationId) {
        loanApplicationQueryService.getApplication(applicationId);
        Optional<LoanAccount> account = loanAccountRepository.findByLoanApplication_Id(applicationId);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        UUID loanAccountId = account.get().getId();

        Optional<DisbursementIntent> liveIntent =
                disbursementIntentRepository.findLiveByLoanAccountId(loanAccountId);
        if (liveIntent.isPresent()) {
            DisbursementIntent intent = liveIntent.get();
            return Optional.of(new DisbursementReference(
                    intent.getTranRefNo(),
                    DisbursementReference.SOURCE_INTENT,
                    intent.getId(),
                    intent.getState().name()
            ));
        }

        return loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccountId)
                .flatMap(this::fromRequestLog);
    }

    private Optional<DisbursementReference> fromRequestLog(LoanDisbursementRequestLog log) {
        if (log.getProviderRequestId() != null && !log.getProviderRequestId().isBlank()) {
            return Optional.of(new DisbursementReference(
                    log.getProviderRequestId(),
                    DisbursementReference.SOURCE_REQUEST_LOG,
                    null,
                    null
            ));
        }
        String json = log.getRequestPayloadJson();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode tran = node.get("tranRefNo");
            if (tran != null && tran.isTextual() && !tran.asText().isBlank()) {
                return Optional.of(new DisbursementReference(
                        tran.asText(),
                        DisbursementReference.SOURCE_REQUEST_LOG,
                        null,
                        null
                ));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
