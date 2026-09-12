package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.AlertContextJson;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
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
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final BorrowerBankUpdatePolicy bankUpdatePolicy;
    private final EntityManager entityManager;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;
    private final ObjectMapper objectMapper;

    public BorrowerOnboardingService(
            BorrowerRepository borrowerRepository,
            OpsAlertService opsAlertService,
            BorrowerActiveLoanChecker borrowerActiveLoanChecker,
            BorrowerLspRelationshipService borrowerLspRelationshipService,
            BorrowerBankUpdatePolicy bankUpdatePolicy,
            EntityManager entityManager,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor,
            ObjectMapper objectMapper
    ) {
        this.borrowerRepository = borrowerRepository;
        this.opsAlertService = opsAlertService;
        this.borrowerActiveLoanChecker = borrowerActiveLoanChecker;
        this.borrowerLspRelationshipService = borrowerLspRelationshipService;
        this.bankUpdatePolicy = bankUpdatePolicy;
        this.entityManager = entityManager;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve (find-or-create) the global borrower for an onboarding request. Cross-tenant PAN
     * and mobile dedup run as narrow admin reads (ADR 0005); every write — visibility grant,
     * profile merge and bank-instruction change — stays in the caller's tenant transaction
     * with row-level security enforced, so a failed onboarding leaves no access, profile or
     * audit delta behind.
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
            // Atomic existing-borrower path, all in this tenant transaction:
            // 1. insert only our own access row (V43 permits lsp_id = self) — no detached
            //    full-entity merge, no separately committed admin write;
            // 2. lock and refresh the borrower so the post-wait row is authoritative;
            // 3. recheck global eligibility under the lock (an approval may have committed
            //    an open loan between the pre-check and the lock);
            // 4. merge the non-bank profile, route changed bank fields through the common
            //    audited policy, and record the relationship row. Any failure rolls back
            //    access, profile and audit together.
            UUID borrowerId = borrowerByPan.getId();
            borrowerLspRelationshipService.grantAccess(borrowerId, lsp.getId());
            Borrower locked = borrowerRepository.findByIdForUpdate(borrowerId)
                    .orElseThrow(() -> new ApiConflictException(
                            "BORROWER_IDENTITY_CONFLICT",
                            "Borrower identity conflict detected. Internal ops has been alerted."
                    ));
            entityManager.refresh(locked);
            raiseActiveLoanDuplicateIfPresent(lsp, locked, profile, actorUsername);
            locked.mergeLatestProfileExcludingBank(profile);
            borrowerRepository.save(locked);
            // Omitted bank fields preserve the existing instruction (merge contract); only
            // supplied fields can change it, through the shared audited policy. Onboarding
            // acts on the originating LSP's behalf through the shared instruction.
            bankUpdatePolicy.applyChangedBankDetails(
                    locked,
                    lsp,
                    effectiveBankValue(profile.bankAccountNumber(), locked.getBankAccountNumber()),
                    effectiveBankValue(profile.bankName(), locked.getBankName()),
                    effectiveBankValue(profile.ifscCode(), locked.getIfscCode()),
                    effectiveBankValue(profile.accountHolderName(), locked.getAccountHolderName()),
                    actorUsername,
                    "LSP_API_CLIENT",
                    null
            );
            borrowerLspRelationshipService.recordRelationship(
                    borrowerId,
                    lsp.getId(),
                    BorrowerLspRelationship.SOURCE_LOAN_ONBOARDING
            );
            return locked;
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

    /**
     * Merge contract: an omitted (blank) incoming bank field preserves the
     * existing instruction instead of clearing it — clearing would both surprise the other
     * LSP sharing the instruction and violate the audit row's NOT NULL beneficiary columns.
     * Explicit edits keep their own null semantics in {@code BorrowerBankDetailsService}.
     */
    private static String effectiveBankValue(String incoming, String current) {
        return Strings.normalizeOptional(incoming) == null ? current : incoming;
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
