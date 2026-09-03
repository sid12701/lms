package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.AlertContextJson;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerLspRelationship;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
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
    private final BorrowerLspRelationshipService borrowerLspRelationshipService;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;
    private final ObjectMapper objectMapper;

    public BorrowerOnboardingService(
            BorrowerRepository borrowerRepository,
            OpsAlertService opsAlertService,
            BorrowerActiveLoanChecker borrowerActiveLoanChecker,
            BorrowerLspRelationshipService borrowerLspRelationshipService,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor,
            ObjectMapper objectMapper
    ) {
        this.borrowerRepository = borrowerRepository;
        this.opsAlertService = opsAlertService;
        this.borrowerActiveLoanChecker = borrowerActiveLoanChecker;
        this.borrowerLspRelationshipService = borrowerLspRelationshipService;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve (find-or-create) the global borrower for an onboarding request. Cross-tenant PAN
     * and mobile dedup plus the visibility grant run under explicit admin scope (ADR 0005);
     * profile writes stay on the tenant datasource with row-level security enforced.
     */
    @Transactional
    public Borrower resolveBorrowerForOnboarding(
            Lsp lsp,
            LoanApplicationOnboardingCommand command,
            BigDecimal monthlyIncome,
            BigDecimal annualIncome,
            String actorUsername
    ) {
        BorrowerProfile profile = normalizedProfile(command.borrowerProfile(), monthlyIncome, annualIncome);
        String normalizedPan = profile.panNumber();
        String normalizedMobile = profile.mobileNumber();

        Borrower borrowerByPan = lookupBorrowerByPan(normalizedPan);
        Borrower borrowerByMobile = lookupBorrowerByMobile(normalizedMobile);

        if (borrowerByPan != null) {
            if (borrowerByMobile != null && !borrowerByMobile.getId().equals(borrowerByPan.getId())) {
                raiseBorrowerIdentityConflict(
                        lsp,
                        borrowerByMobile,
                        profile,
                        actorUsername,
                        "Incoming PAN matches an existing borrower, but the submitted mobile number is already associated with a different borrower."
                );
            }
            validateImmutableBorrowerIdentity(lsp, borrowerByPan, profile, actorUsername);
            raiseActiveLoanDuplicateIfPresent(lsp, borrowerByPan, profile, actorUsername);
            // Cross-tenant visibility grant needs admin (borrower row already committed).
            Borrower visibleBorrower = grantVisibilityUnderAdmin(borrowerByPan, lsp);
            visibleBorrower.mergeLatestProfile(profile);
            return borrowerRepository.save(visibleBorrower);
        }

        if (borrowerByMobile != null) {
            raiseBorrowerIdentityConflict(
                    lsp,
                    borrowerByMobile,
                    profile,
                    actorUsername,
                    "Incoming mobile number already belongs to an existing borrower with a different PAN."
            );
        }

        // New borrower: insert + visibility grant must stay in the same tenant transaction.
        // Elevating to a REQUIRES_NEW admin transaction here cannot see the uncommitted insert
        // and Hibernate re-persists the same id → borrower_pkey conflict.
        Borrower borrower = borrowerRepository.save(new Borrower(profile));
        return borrowerLspRelationshipService.grantVisibility(
                borrower,
                lsp,
                BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING
        );
    }

    private Borrower lookupBorrowerByPan(String normalizedPan) {
        return adminScopedTransactionExecutor.call(
                () -> borrowerRepository.findByPan(normalizedPan).orElse(null)
        );
    }

    private Borrower lookupBorrowerByMobile(String normalizedMobile) {
        return adminScopedTransactionExecutor.call(() -> borrowerRepository
                .findTop10ByMobileOrderByUpdatedAtDesc(normalizedMobile)
                .stream()
                .findFirst()
                .orElse(null));
    }

    private Borrower grantVisibilityUnderAdmin(Borrower borrower, Lsp lsp) {
        return adminScopedTransactionExecutor.call(() -> borrowerLspRelationshipService.grantVisibility(
                borrower,
                lsp,
                BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING
        ));
    }

    private BorrowerProfile normalizedProfile(
            BorrowerProfile profile,
            BigDecimal monthlyIncome,
            BigDecimal annualIncome
    ) {
        return profile.withScaledIncomes(monthlyIncome, annualIncome)
                .withNormalizedIdentity(
                        normalizePan(profile.panNumber()),
                        normalizeMobile(profile.mobileNumber()),
                        normalizeFullName(profile.fullName()),
                        normalizeAadhar(profile.aadharNumber()),
                        normalizeEmail(profile.emailAddress())
                );
    }

    private void validateImmutableBorrowerIdentity(
            Lsp lsp,
            Borrower borrower,
            BorrowerProfile profile,
            String actorUsername
    ) {
        String currentAadhar = normalizeAadhar(borrower.getAadharNumber());
        String normalizedAadhar = profile.aadharNumber();
        if (currentAadhar != null && normalizedAadhar != null && !currentAadhar.equals(normalizedAadhar)) {
            raiseBorrowerIdentityConflict(
                    lsp,
                    borrower,
                    profile,
                    actorUsername,
                    "Incoming Aadhaar does not match the existing borrower identity for the submitted PAN."
            );
        }
    }

    private void raiseBorrowerIdentityConflict(
            Lsp lsp,
            Borrower existingBorrower,
            BorrowerProfile profile,
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
                serializeBorrowerConflictContext(lsp, existingBorrower, profile, actorUsername, reason)
        );
        throw new ApiConflictException(
                "BORROWER_IDENTITY_CONFLICT",
                "Borrower identity conflict detected. Internal ops has been alerted."
        );
    }

    private void raiseActiveLoanDuplicateIfPresent(
            Lsp lsp,
            Borrower existingBorrower,
            BorrowerProfile profile,
            String actorUsername
    ) {
        if (existingBorrower == null) {
            return;
        }
        List<LoanAccount> openLoans = borrowerActiveLoanChecker.findOpenLoansAcrossAllLsps(existingBorrower.getId());
        if (openLoans.isEmpty()) {
            return;
        }

        String reason = "Borrower already has " + openLoans.size() + " open "
                + Strings.pluralize(openLoans.size(), "loan") + " across LSPs. "
                + "Concurrent loan onboarding is blocked.";
        opsAlertService.createAlert(
                OpsAlertType.BORROWER_ACTIVE_LOAN_DUPLICATE,
                OpsAlertSeverity.HIGH,
                "Borrower already has an open loan",
                reason + " Internal ops review is required before this borrower can be onboarded for a new loan.",
                "BORROWER",
                existingBorrower.getId(),
                CorrelationIdHolder.get(),
                serializeActiveLoanDuplicateContext(lsp, existingBorrower, openLoans, profile, actorUsername)
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
            BorrowerProfile profile,
            String actorUsername
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorUsername", actorUsername);
        payload.put("incomingLspId", lsp == null ? null : lsp.getId());
        payload.put("incomingLspCode", lsp == null ? null : lsp.getCode());
        payload.put("incomingPan", profile.panNumber());
        payload.put("incomingMobile", profile.mobileNumber());
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
            BorrowerProfile profile,
            String actorUsername,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("actorUsername", Strings.normalizeActor(actorUsername));
        payload.put("lspId", lsp.getId());
        payload.put("lspCode", lsp.getCode());
        payload.put("incomingPan", profile.panNumber());
        payload.put("incomingMobile", profile.mobileNumber());
        payload.put("incomingAadhar", profile.aadharNumber());
        payload.put("incomingFullName", profile.fullName());
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
