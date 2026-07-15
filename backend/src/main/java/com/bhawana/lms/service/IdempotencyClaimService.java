package com.bhawana.lms.service;

import com.bhawana.lms.domain.AdminApiIdempotencyRecord;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import java.util.UUID;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyClaimService {

    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;
    private final AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public IdempotencyClaimService(
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.adminApiIdempotencyRecordRepository = adminApiIdempotencyRecordRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LoanPaymentTransaction claimLoanPaymentRow(LoanPaymentTransaction paymentTransaction) {
        return loanPaymentTransactionRepository.saveAndFlush(paymentTransaction);
    }

    public boolean claimLspApiIdempotencyRecord(LspApiIdempotencyRecord record) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(
                    status -> lspApiIdempotencyRecordRepository.saveAndFlush(record)
            );
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    public boolean claimAdminApiIdempotencyRecord(AdminApiIdempotencyRecord record) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(
                    status -> adminApiIdempotencyRecordRepository.saveAndFlush(record)
            );
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    public boolean completeLspApiIdempotencyRecord(
            LeaseToken leaseToken,
            int responseStatus,
            String responseBody
    ) {
        return lspApiIdempotencyRecordRepository.completeIfOwned(
                leaseToken.recordId(),
                leaseToken.attempt(),
                leaseToken.leaseOwner(),
                responseStatus,
                responseBody,
                IdempotencyRecordState.PENDING_RESPONSE_BODY,
                Instant.now()
        ) == 1;
    }

    public void releasePendingLspApiIdempotencyRecord(LeaseToken leaseToken) {
        requiresNewTransactionTemplate.executeWithoutResult(status ->
                lspApiIdempotencyRecordRepository.deletePendingIfOwned(
                        leaseToken.recordId(),
                        leaseToken.attempt(),
                        leaseToken.leaseOwner(),
                        IdempotencyRecordState.PENDING_RESPONSE_BODY
                ));
    }

    public boolean completeAdminApiIdempotencyRecord(
            LeaseToken leaseToken,
            int responseStatus,
            String responseBody
    ) {
        return adminApiIdempotencyRecordRepository.completeIfOwned(
                leaseToken.recordId(),
                leaseToken.attempt(),
                leaseToken.leaseOwner(),
                responseStatus,
                responseBody,
                IdempotencyRecordState.PENDING_RESPONSE_BODY,
                Instant.now()
        ) == 1;
    }

    public void releasePendingAdminApiIdempotencyRecord(LeaseToken leaseToken) {
        requiresNewTransactionTemplate.executeWithoutResult(status ->
                adminApiIdempotencyRecordRepository.deletePendingIfOwned(
                        leaseToken.recordId(),
                        leaseToken.attempt(),
                        leaseToken.leaseOwner(),
                        IdempotencyRecordState.PENDING_RESPONSE_BODY
                ));
    }

    public Optional<LeaseToken> tryReclaimExpiredLspApiIdempotencyLease(
            UUID recordId,
            int currentAttempt,
            String leaseOwner,
            Instant leaseExpiresAt
    ) {
        Instant now = Instant.now();
        Integer updated = requiresNewTransactionTemplate.execute(status ->
                lspApiIdempotencyRecordRepository.tryReclaimExpiredLease(
                        recordId,
                        leaseOwner,
                        leaseExpiresAt,
                        now,
                        IdempotencyRecordState.PENDING_RESPONSE_BODY
                )
        );
        return updated != null && updated > 0
                ? Optional.of(new LeaseToken(recordId, currentAttempt + 1, leaseOwner))
                : Optional.empty();
    }

    public Optional<LeaseToken> tryReclaimExpiredAdminApiIdempotencyLease(
            UUID recordId,
            int currentAttempt,
            String leaseOwner,
            Instant leaseExpiresAt
    ) {
        Instant now = Instant.now();
        Integer updated = requiresNewTransactionTemplate.execute(status ->
                adminApiIdempotencyRecordRepository.tryReclaimExpiredLease(
                        recordId,
                        leaseOwner,
                        leaseExpiresAt,
                        now,
                        IdempotencyRecordState.PENDING_RESPONSE_BODY
                )
        );
        return updated != null && updated > 0
                ? Optional.of(new LeaseToken(recordId, currentAttempt + 1, leaseOwner))
                : Optional.empty();
    }

    public record LeaseToken(UUID recordId, int attempt, String leaseOwner) {
    }
}
