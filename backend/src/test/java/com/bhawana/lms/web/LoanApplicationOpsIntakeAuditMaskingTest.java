package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoanApplicationOpsIntakeAuditMaskingTest {

    @Test
    void intakeAuditResponseMasksBorrowerAadharNumberInPayloadJson() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        LoanApplicationIntakeAudit audit = new LoanApplicationIntakeAudit(
                application,
                "ops.user",
                "corr-1",
                """
                        {
                          "externalLoanId": "EXT-MASK-001",
                          "borrowerAadharNumber": "123412341234",
                          "borrowerPan": "ABCDE1234F"
                        }
                        """
        );

        LoanApplicationOpsApiTypes.LoanApplicationIntakeAuditResponse response =
                LoanApplicationOpsResponses.toAuditResponse(audit);

        assertTrue(response.payloadJson().contains("\"borrowerAadharNumber\":\"XXXXXXXX1234\""));
        assertFalse(response.payloadJson().contains("123412341234"));
        assertTrue(response.payloadJson().contains("\"borrowerPan\":\"ABCDE1234F\""));
    }
}
