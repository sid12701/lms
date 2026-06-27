package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.service.LoanDisbursementAdapter.DisbursementCommand;
import com.bhawana.lms.service.LoanDisbursementAdapter.DisbursementResult;
import com.bhawana.lms.service.LoanDisbursementAdapter.DisbursementStatusQuery;
import com.bhawana.lms.service.LoanDisbursementAdapter.DisbursementStatusResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MockLoanDisbursementAdapterScenarioTest {

    private final MockLoanDisbursementAdapter adapter =
            new MockLoanDisbursementAdapter(new ObjectMapper(), new LoanDisbursementMockProperties());

    private DisbursementResult request(DisbursementPaymentMode mode, String ifsc) {
        return adapter.requestDisbursement(new DisbursementCommand(
                "LMS-LN-1", new BigDecimal("10000.00"), "Asha Borrower", "EXT-1", "LSP-A",
                mode, "ICITESTREF000001", "123456789012", ifsc
        ));
    }

    @Test
    void defaultIfscIsSynchronousImpsSuccess() {
        DisbursementResult result = request(DisbursementPaymentMode.IMPS, "HDFC0001234");
        assertEquals(DisbursementDisposition.SUCCESS, result.disposition());
        assertEquals(DisbursementDeclineKind.NONE, result.declineKind());
        assertEquals("0", result.actCode());
        assertEquals(DisbursementPaymentMode.IMPS, result.paymentMode());
        assertEquals(MockLoanDisbursementAdapter.PROVIDER_NAME, result.providerName());
        assertNotNull(result.bankRrn());
    }

    @Test
    void insufficientFundsIsBusinessDecline() {
        DisbursementResult result = request(DisbursementPaymentMode.IMPS, "MOCK0INSUFF");
        assertEquals(DisbursementDisposition.FAILED, result.disposition());
        assertEquals(DisbursementDeclineKind.BUSINESS, result.declineKind());
        assertEquals("51", result.actCode());
    }

    @Test
    void npciDownIsTechnicalDecline() {
        DisbursementResult result = request(DisbursementPaymentMode.IMPS, "MOCK0NPCIDN");
        assertEquals(DisbursementDisposition.FAILED, result.disposition());
        assertEquals(DisbursementDeclineKind.TECHNICAL, result.declineKind());
        assertEquals("18", result.actCode());
    }

    @Test
    void duplicateIsDeclinedSynchronously() {
        DisbursementResult result = request(DisbursementPaymentMode.IMPS, "MOCK0DUPLIC");
        assertEquals(DisbursementDisposition.FAILED, result.disposition());
        assertEquals("14", result.actCode());
    }

    @Test
    void timeoutScenarioReturnsPendingOnRequest() {
        DisbursementResult result = request(DisbursementPaymentMode.IMPS, "MOCK0PENDOK");
        assertEquals(DisbursementDisposition.PENDING, result.disposition());
    }

    @Test
    void neftIsAlwaysDeferredEvenForSuccess() {
        DisbursementResult result = request(DisbursementPaymentMode.NEFT, "HDFC0001234");
        assertEquals(DisbursementDisposition.PENDING, result.disposition());
        assertEquals(DisbursementPaymentMode.NEFT, result.paymentMode());
    }

    @Test
    void statusCheckTooEarlyIsNotQueryable() {
        DisbursementStatusResult result = adapter.checkStatus(new DisbursementStatusQuery(
                "ICITESTREF000001", DisbursementPaymentMode.IMPS, "MOCK0PENDOK", 0
        ));
        assertFalse(result.isQueryResolved());
        assertEquals("100", result.checkStatusCode());
    }

    @Test
    void statusCheckResolvesToSuccessAfterMinPolls() {
        DisbursementStatusResult result = adapter.checkStatus(new DisbursementStatusQuery(
                "ICITESTREF000001", DisbursementPaymentMode.IMPS, "MOCK0PENDOK", 1
        ));
        assertTrue(result.isQueryResolved());
        assertEquals(DisbursementDisposition.SUCCESS, result.disposition());
        assertEquals(DisbursementDeclineKind.NONE, result.declineKind());
    }

    @Test
    void statusCheckResolvesToTechnicalFailure() {
        DisbursementStatusResult result = adapter.checkStatus(new DisbursementStatusQuery(
                "ICITESTREF000001", DisbursementPaymentMode.IMPS, "MOCK0PENDFL", 1
        ));
        assertTrue(result.isQueryResolved());
        assertEquals(DisbursementDisposition.FAILED, result.disposition());
        assertEquals(DisbursementDeclineKind.TECHNICAL, result.declineKind());
    }

    @Test
    void stuckScenarioStaysPendingButQueryable() {
        DisbursementStatusResult result = adapter.checkStatus(new DisbursementStatusQuery(
                "ICITESTREF000001", DisbursementPaymentMode.NEFT, "MOCK0STUCK0", 5
        ));
        assertTrue(result.isQueryResolved());
        assertEquals(DisbursementDisposition.PENDING, result.disposition());
        assertEquals("11", result.actCode());
    }
}
