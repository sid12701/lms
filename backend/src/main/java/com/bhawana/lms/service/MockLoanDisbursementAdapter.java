package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * In-process stand-in for ICICI Composite Pay that mirrors the real money-movement lifecycle without
 * any network or encryption: a payment request returns a synchronous terminal verdict (IMPS) or an
 * accepted/pending acknowledgement (NEFT, and IMPS timeout codes), after which {@link #checkStatus}
 * resolves the terminal status. Outcomes are deterministic, keyed off the beneficiary IFSC via
 * {@link MockIciciDisbursementScenario}. The production adapter will replace this behind the same seam.
 */
@Service
public class MockLoanDisbursementAdapter implements LoanDisbursementAdapter {

    static final String PROVIDER_NAME = "MOCK_ICICI";

    private final ObjectMapper objectMapper;
    private final LoanDisbursementMockProperties properties;

    public MockLoanDisbursementAdapter(ObjectMapper objectMapper, LoanDisbursementMockProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public DisbursementResult requestDisbursement(DisbursementCommand command) {
        MockIciciDisbursementScenario scenario = MockIciciDisbursementScenario.forIfsc(command.beneficiaryIfsc());
        DisbursementPaymentMode mode = command.paymentMode() != null
                ? command.paymentMode()
                : DisbursementPaymentMode.IMPS;
        DisbursementDisposition disposition = requestDisposition(scenario, mode);

        String bankRrn = disposition == DisbursementDisposition.FAILED ? null : generateBankRrn();
        String actCode = disposition == DisbursementDisposition.PENDING ? pendingActCode(scenario) : scenario.actCode();
        DisbursementDeclineKind declineKind = disposition == DisbursementDisposition.FAILED
                ? scenario.declineKind()
                : DisbursementDeclineKind.NONE;
        String message = disposition == DisbursementDisposition.PENDING
                ? "Accepted by " + mode + "; awaiting terminal status (status check required)."
                : scenario.message();

        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("provider", PROVIDER_NAME);
        response.put("paymentMode", mode.name());
        response.put("xPriority", mode.xPriority());
        response.put("tranRefNo", command.tranRefNo());
        response.put("ActCode", actCode);
        response.put("Response", message);
        response.put("BankRRN", bankRrn == null ? "" : bankRrn);
        response.put("success", disposition != DisbursementDisposition.FAILED);
        response.put("disposition", disposition.name());
        response.put("declineKind", declineKind.name());
        response.put("loanAccountNumber", command.loanAccountNumber());
        response.put("beneficiaryAccountNumber", command.beneficiaryAccountNumber());
        response.put("beneficiaryIfsc", command.beneficiaryIfsc());
        response.put("amount", command.amount());

        return new DisbursementResult(
                PROVIDER_NAME,
                command.tranRefNo(),
                providerStatusFor(disposition),
                mode,
                disposition,
                declineKind,
                actCode,
                bankRrn,
                message,
                serialize(response)
        );
    }

    @Override
    public DisbursementStatusResult checkStatus(DisbursementStatusQuery query) {
        MockIciciDisbursementScenario scenario = MockIciciDisbursementScenario.forIfsc(query.beneficiaryIfsc());

        // ICICI: a status check raised too early returns CheckStatusCode 100/17 (not yet queryable).
        boolean queryableYet = query.priorStatusCheckCount() >= properties.getStatusCheck().getMinPolls();
        if (!queryableYet && !scenario.neverResolves()) {
            return notYetQueryable(query, scenario);
        }

        // A transaction that never reaches a terminal status keeps reporting PENDING; the worker
        // parks it for reconciliation once the poll cap is hit.
        DisbursementDisposition disposition = scenario.neverResolves()
                ? DisbursementDisposition.PENDING
                : scenario.terminalDisposition();
        DisbursementDeclineKind declineKind = disposition == DisbursementDisposition.FAILED
                ? scenario.declineKind()
                : DisbursementDeclineKind.NONE;
        String actCode = disposition == DisbursementDisposition.SUCCESS ? "0" : scenario.actCode();
        String bankRrn = disposition == DisbursementDisposition.FAILED ? null : generateBankRrn();
        String message = disposition == DisbursementDisposition.PENDING
                ? "Transaction still pending at provider."
                : scenario.message();

        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("provider", PROVIDER_NAME);
        response.put("CheckStatusCode", "0");
        response.put("CheckStatusMessage", "Check Transaction Successful");
        response.put("tranRefNo", query.tranRefNo());
        response.put("ActCode", actCode);
        response.put("Response", message);
        response.put("BankRRN", bankRrn == null ? "" : bankRrn);
        response.put("disposition", disposition.name());
        response.put("declineKind", declineKind.name());

        return new DisbursementStatusResult(
                "0",
                "Check Transaction Successful",
                disposition,
                declineKind,
                actCode,
                bankRrn,
                message,
                serialize(response)
        );
    }

    private DisbursementStatusResult notYetQueryable(
            DisbursementStatusQuery query,
            MockIciciDisbursementScenario scenario
    ) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("provider", PROVIDER_NAME);
        response.put("CheckStatusCode", "100");
        response.put("CheckStatusMessage", "Status check performed too early; retry after the configured interval.");
        response.put("tranRefNo", query.tranRefNo());
        response.put("ActCode", scenario.actCode());
        return new DisbursementStatusResult(
                "100",
                "Status check performed too early; retry after the configured interval.",
                DisbursementDisposition.PENDING,
                DisbursementDeclineKind.NONE,
                scenario.actCode(),
                null,
                "Status check performed too early; retry after the configured interval.",
                serialize(response)
        );
    }

    private static DisbursementDisposition requestDisposition(
            MockIciciDisbursementScenario scenario,
            DisbursementPaymentMode mode
    ) {
        if (scenario.isDuplicate()) {
            return DisbursementDisposition.FAILED;
        }
        if (mode == DisbursementPaymentMode.NEFT) {
            return DisbursementDisposition.PENDING;
        }
        return scenario.pendingFirst() ? DisbursementDisposition.PENDING : scenario.terminalDisposition();
    }

    private static String pendingActCode(MockIciciDisbursementScenario scenario) {
        // Timeout/deemed codes surface their ActCode; a plain NEFT acceptance has no decline code.
        return scenario.pendingFirst() || scenario.neverResolves() ? scenario.actCode() : "0";
    }

    private static String providerStatusFor(DisbursementDisposition disposition) {
        return switch (disposition) {
            case SUCCESS -> "SUCCESS";
            case FAILED -> "FAILED";
            case PENDING -> "PENDING";
        };
    }

    private static String generateBankRrn() {
        long rrn = ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L);
        return Long.toString(rrn);
    }

    private String serialize(LinkedHashMap<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize mock disbursement response.", exception);
        }
    }
}
