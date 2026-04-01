package com.bhawana.lms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MockLoanDisbursementAdapter implements LoanDisbursementAdapter {

    private final ObjectMapper objectMapper;

    public MockLoanDisbursementAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DisbursementResult requestDisbursement(DisbursementCommand command) {
        String providerRequestId = "MDB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("provider", "MOCK_DISBURSEMENT");
        response.put("providerRequestId", providerRequestId);
        response.put("status", "ACCEPTED");
        response.put("loanAccountNumber", command.loanAccountNumber());
        response.put("externalLoanId", command.externalLoanId());
        response.put("amount", command.amount());
        response.put("message", "Mock disbursement request accepted for downstream reconciliation.");
        return new DisbursementResult(
                "MOCK_DISBURSEMENT",
                providerRequestId,
                "ACCEPTED",
                serialize(response)
        );
    }

    private String serialize(LinkedHashMap<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize mock disbursement response.", exception);
        }
    }
}
