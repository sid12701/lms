package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.AlertContextJson;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.BorrowerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BorrowerOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(BorrowerOnboardingService.class);

    private final BorrowerRepository borrowerRepository;
    private final OpsAlertService opsAlertService;
    private final BorrowerActiveLoanChecker borrowerActiveLoanChecker;
    private final ObjectMapper objectMapper;

    public BorrowerOnboardingService(
            BorrowerRepository borrowerRepository,
            OpsAlertService opsAlertService,
            BorrowerActiveLoanChecker borrowerActiveLoanChecker,
            ObjectMapper objectMapper
    ) {
        this.borrowerRepository = borrowerRepository;
        this.opsAlertService = opsAlertService;
        this.borrowerActiveLoanChecker = borrowerActiveLoanChecker;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Borrower resolveBorrowerForOnboarding(
            Lsp lsp,
            LoanApplicationOnboardingCommand command,
            BigDecimal monthlyIncome,
            BigDecimal annualIncome,
            String actorUsername
    ) {
        String normalizedPan = normalizePan(command.panNumber());
        String normalizedMobile = normalizeMobile(command.mobileNumber());
        String normalizedAadhar = normalizeAadhar(command.aadharNumber());
        String normalizedFullName = normalizeFullName(command.fullName());

        Borrower borrowerByPan = borrowerRepository.findByPan(normalizedPan).orElse(null);
        Borrower borrowerByMobile = borrowerRepository.findTop10ByMobileOrderByUpdatedAtDesc(normalizedMobile)
                .stream()
                .findFirst()
                .orElse(null);

        if (borrowerByPan != null) {
            if (borrowerByMobile != null && !borrowerByMobile.getId().equals(borrowerByPan.getId())) {
                raiseBorrowerIdentityConflict(
                        lsp,
                        borrowerByMobile,
                        command,
                        actorUsername,
                        "Incoming PAN matches an existing borrower, but the submitted mobile number is already associated with a different borrower."
                );
            }
            validateImmutableBorrowerIdentity(lsp, borrowerByPan, normalizedAadhar, command, actorUsername);
            raiseActiveLoanDuplicateIfPresent(lsp, borrowerByPan, command, actorUsername);
            borrowerByPan.mergeLatestProfile(
                    normalizedFullName,
                    normalizedMobile,
                    normalizeEmail(command.emailAddress()),
                    command.dob(),
                    command.gender(),
                    command.maritalStatus(),
                    command.fatherName(),
                    normalizedAadhar,
                    command.addressCity(),
                    command.addressState(),
                    command.addressLine1(),
                    command.addressLine2(),
                    command.addressZipcode(),
                    command.spouseName(),
                    command.employmentStatus(),
                    command.organizationName(),
                    command.empId(),
                    command.employmentCity(),
                    command.employmentState(),
                    command.employmentZip(),
                    monthlyIncome,
                    annualIncome,
                    command.bankAccountNumber(),
                    command.bankName(),
                    command.ifscCode(),
                    command.accountHolderName(),
                    command.referencePersonName(),
                    command.referencePersonNumber()
            );
            borrowerByPan.grantVisibilityTo(lsp);
            return borrowerRepository.save(borrowerByPan);
        }

        if (borrowerByMobile != null) {
            raiseBorrowerIdentityConflict(
                    lsp,
                    borrowerByMobile,
                    command,
                    actorUsername,
                    "Incoming mobile number already belongs to an existing borrower with a different PAN."
            );
        }

        Borrower borrower = new Borrower(
                normalizedFullName,
                normalizedPan,
                normalizedMobile,
                normalizeEmail(command.emailAddress()),
                command.dob(),
                command.gender(),
                command.maritalStatus(),
                command.fatherName(),
                normalizedAadhar,
                command.addressCity(),
                command.addressState(),
                command.addressLine1(),
                command.addressLine2(),
                command.addressZipcode(),
                command.spouseName(),
                command.employmentStatus(),
                command.organizationName(),
                command.empId(),
                command.employmentCity(),
                command.employmentState(),
                command.employmentZip(),
                monthlyIncome,
                annualIncome,
                command.bankAccountNumber(),
                command.bankName(),
                command.ifscCode(),
                command.accountHolderName(),
                command.referencePersonName(),
                command.referencePersonNumber()
        );
        borrower.grantVisibilityTo(lsp);
        return borrowerRepository.save(borrower);
    }

    private void validateImmutableBorrowerIdentity(
            Lsp lsp,
            Borrower borrower,
            String normalizedAadhar,
            LoanApplicationOnboardingCommand command,
            String actorUsername
    ) {
        String currentAadhar = normalizeAadhar(borrower.getAadharNumber());
        if (currentAadhar != null && normalizedAadhar != null && !currentAadhar.equals(normalizedAadhar)) {
            raiseBorrowerIdentityConflict(
                    lsp,
                    borrower,
                    command,
                    actorUsername,
                    "Incoming Aadhaar does not match the existing borrower identity for the submitted PAN."
            );
        }
    }

    private void raiseBorrowerIdentityConflict(
            Lsp lsp,
            Borrower existingBorrower,
            LoanApplicationOnboardingCommand command,
            String actorUsername,
            String reason
    ) {
        opsAlertService.createAlert(
                OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                OpsAlertSeverity.HIGH,
                "Borrower identity mismatch detected",
                reason + " Internal ops review is required before this borrower can be onboarded again.",
                "BORROWER",
                existingBorrower == null ? null : existingBorrower.getId(),
                CorrelationIdHolder.get(),
                serializeBorrowerConflictContext(lsp, existingBorrower, command, actorUsername, reason)
        );
        throw new ApiConflictException(
                "BORROWER_IDENTITY_CONFLICT",
                "Borrower identity conflict detected. Internal ops has been alerted."
        );
    }

    private void raiseActiveLoanDuplicateIfPresent(
            Lsp lsp,
            Borrower existingBorrower,
            LoanApplicationOnboardingCommand command,
            String actorUsername
    ) {
        if (existingBorrower == null) {
            return;
        }
        List<LoanAccount> openLoans = borrowerActiveLoanChecker.findOpenLoansAcrossAllLsps(existingBorrower.getId());
        if (openLoans.isEmpty()) {
            return;
        }

        String reason = "Borrower already has " + openLoans.size() + " open loan(s) across LSPs. "
                + "Concurrent loan onboarding is blocked.";
        opsAlertService.createAlert(
                OpsAlertType.BORROWER_ACTIVE_LOAN_DUPLICATE,
                OpsAlertSeverity.HIGH,
                "Borrower already has an open loan",
                reason + " Internal ops review is required before this borrower can be onboarded for a new loan.",
                "BORROWER",
                existingBorrower.getId(),
                CorrelationIdHolder.get(),
                serializeActiveLoanDuplicateContext(lsp, existingBorrower, openLoans, command, actorUsername)
        );
        throw new ApiConflictException(
                "BORROWER_HAS_ACTIVE_LOAN",
                "Borrower already has an open loan. Onboarding blocked."
        );
    }

    private String serializeActiveLoanDuplicateContext(
            Lsp lsp,
            Borrower existingBorrower,
            List<LoanAccount> openLoans,
            LoanApplicationOnboardingCommand command,
            String actorUsername
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorUsername", actorUsername);
        payload.put("incomingLspId", lsp == null ? null : lsp.getId());
        payload.put("incomingLspCode", lsp == null ? null : lsp.getCode());
        payload.put("incomingPan", normalizePan(command.panNumber()));
        payload.put("incomingMobile", normalizeMobile(command.mobileNumber()));
        payload.put("borrowerId", existingBorrower.getId());
        payload.put("borrowerPan", existingBorrower.getPan());
        List<Map<String, Object>> loanEntries = new ArrayList<>(openLoans.size());
        for (LoanAccount loan : openLoans) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("loanAccountId", loan.getId());
            entry.put("applicationId", loan.getLoanApplication() == null ? null : loan.getLoanApplication().getId());
            entry.put("lspId", loan.getLsp() == null ? null : loan.getLsp().getId());
            entry.put("lspCode", loan.getLsp() == null ? null : loan.getLsp().getCode());
            entry.put("status", loan.getStatus() == null ? null : loan.getStatus().name());
            loanEntries.add(entry);
        }
        payload.put("openLoans", loanEntries);
        return AlertContextJson.serialize(objectMapper, log, payload);
    }

    private String serializeBorrowerConflictContext(
            Lsp lsp,
            Borrower existingBorrower,
            LoanApplicationOnboardingCommand command,
            String actorUsername,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("actorUsername", Strings.normalizeActor(actorUsername));
        payload.put("lspId", lsp.getId());
        payload.put("lspCode", lsp.getCode());
        payload.put("incomingPan", normalizePan(command.panNumber()));
        payload.put("incomingMobile", normalizeMobile(command.mobileNumber()));
        payload.put("incomingAadhar", normalizeAadhar(command.aadharNumber()));
        payload.put("incomingFullName", normalizeFullName(command.fullName()));
        if (existingBorrower != null) {
            payload.put("existingBorrowerId", existingBorrower.getId());
            payload.put("existingPan", existingBorrower.getPan());
            payload.put("existingMobile", existingBorrower.getMobile());
            payload.put("existingAadhar", existingBorrower.getAadharNumber());
            payload.put("existingFullName", existingBorrower.getFullName());
            payload.put("existingVisibleLspIds", existingBorrower.getVisibleLspIds());
        }
        return AlertContextJson.serialize(objectMapper, log, payload);
    }

    static String normalizePan(String pan) {
        return pan.trim().toUpperCase();
    }

    static String normalizeMobile(String mobile) {
        return mobile.trim();
    }

    static String normalizeFullName(String fullName) {
        return fullName.trim();
    }

    static String normalizeAadhar(String aadharNumber) {
        String normalized = Strings.normalizeOptional(aadharNumber);
        return normalized == null ? null : normalized.replace(" ", "");
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim();
        return normalized.isBlank() ? null : normalized.toLowerCase();
    }
}
