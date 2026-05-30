package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MockDisbursementOutcomeRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesOutcomeEnumFromJson() throws Exception {
        MockDisbursementOutcomeRequest request = objectMapper.readValue(
                "{\"outcome\":\"DISBURSED\"}",
                MockDisbursementOutcomeRequest.class
        );

        assertThat(request.outcome()).isEqualTo(MockDisbursementOutcome.DISBURSED);
    }
}
