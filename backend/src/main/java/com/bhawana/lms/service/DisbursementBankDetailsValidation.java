package com.bhawana.lms.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DisbursementBankDetailsValidation(
        Map<String, String> violations,
        List<BankDetailWarning> warnings
) {

    public static DisbursementBankDetailsValidation empty() {
        return new DisbursementBankDetailsValidation(Map.of(), List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, String> violations = new LinkedHashMap<>();
        private final List<BankDetailWarning> warnings = new ArrayList<>();

        public Builder violation(String field, String message) {
            violations.put(field, message);
            return this;
        }

        public Builder warning(BankDetailWarning warning) {
            warnings.add(warning);
            return this;
        }

        public DisbursementBankDetailsValidation build() {
            return new DisbursementBankDetailsValidation(
                    Map.copyOf(violations),
                    List.copyOf(warnings)
            );
        }
    }

    public record BankDetailWarning(String field, String code, String message) {
    }
}
