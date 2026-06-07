package com.bhawana.lms.service;

import com.bhawana.lms.service.DisbursementBankDetailsValidation.BankDetailWarning;
import java.util.List;

public record BankDetailsCheckResult(String status, List<BankDetailWarning> warnings) {

    public static BankDetailsCheckResult ok() {
        return new BankDetailsCheckResult("OK", List.of());
    }

    public static BankDetailsCheckResult warn(List<BankDetailWarning> warnings) {
        return new BankDetailsCheckResult("WARN", List.copyOf(warnings));
    }
}
