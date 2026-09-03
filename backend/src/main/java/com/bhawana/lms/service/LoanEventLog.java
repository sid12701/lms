package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The single seam through which every loan lifecycle fact is recorded (ADR 0007).
 *
 * <p>Every producer in the platform appends here, in the same transaction as the state change it
 * describes. Delivery is deliberately not this class's concern: it records what happened, and who may
 * read a given event is resolved at read time.
 *
 * <p>The {@link LoanEventType} type describes the lifecycle fact recorded here. It is written
 * unconditionally: there is no subscription and no enable flag gating whether a fact is appended
 * (ADR 0007 decision 1).
 */
@Service
public class LoanEventLog {

    private final LoanEventRepository loanEventRepository;
    private final ObjectMapper objectMapper;

    public LoanEventLog(
            LoanEventRepository loanEventRepository,
            ObjectMapper objectMapper
    ) {
        this.loanEventRepository = loanEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records one loan lifecycle fact for the LSP that owns the loan.
     *
     * <p>Call this in the same transaction as the state change being described, so the record and the
     * fact commit together or not at all.
     */
    public void append(
            Lsp lsp,
            LoanEventType eventType,
            String aggregateType,
            String aggregateId,
            UUID loanApplicationId,
            Map<String, Object> payload
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Loan events must be appended inside the lifecycle transaction.");
        }
        Instant occurredAt = Instant.now();
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("eventType", eventType.name());
        envelope.put("occurredAt", occurredAt);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("lspId", lsp.getId());
        envelope.put("lspCode", lsp.getCode());
        envelope.put("payload", payload);

        loanEventRepository.append(
                UUID.randomUUID(),
                lsp.getId(),
                eventType,
                aggregateType,
                aggregateId,
                loanApplicationId,
                serializePayload(envelope),
                occurredAt,
                CorrelationIdHolder.get()
        );
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize loan event payload.", exception);
        }
    }
}
