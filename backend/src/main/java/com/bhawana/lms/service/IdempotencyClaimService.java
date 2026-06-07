package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
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
    private final TransactionTemplate requiresNewTransactionTemplate;

    public IdempotencyClaimService(
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<LoanPaymentTransaction> claimLoanPaymentRow(LoanPaymentTransaction paymentTransaction) {
        try {
            LoanPaymentTransaction saved = requiresNewTransactionTemplate.execute(
                    status -> loanPaymentTransactionRepository.saveAndFlush(paymentTransaction)
            );
            return Optional.ofNullable(saved);
        } catch (DataIntegrityViolationException exception) {
            return Optional.empty();
        }
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
}
