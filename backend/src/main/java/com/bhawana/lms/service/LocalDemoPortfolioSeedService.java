package com.bhawana.lms.service;

import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalDemoPortfolioSeedService {

    private static final String DEMO_LSP_CODE = "SUPAONE";
    private static final String DEMO_PRODUCT_CODE = "SUPA-FLEX";
    private static final String DEFAULT_USER_PASSWORD = "DemoPass123!";
    private static final String INTERNAL_ACTOR = "ops.admin";

    private static final Logger log = LoggerFactory.getLogger(LocalDemoPortfolioSeedService.class);
    private static final int RESET_MAX_ATTEMPTS = 5;
    private static final long RESET_RETRY_BACKOFF_MILLIS = 250L;

    private final LspDirectoryService lspDirectoryService;
    private final UserAdminService userAdminService;
    private final ProductConfigurationService productConfigurationService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationServicingReadService loanApplicationServicingReadService;
    private final LoanDisbursementCommandService loanDisbursementCommandService;
    private final LoanRepaymentCommandService loanRepaymentCommandService;
    private final LoanForeclosureCommandService loanForeclosureCommandService;
    private final AppUserRepository appUserRepository;
    private final LspRepository lspRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SecurityProperties securityProperties;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;

    public LocalDemoPortfolioSeedService(
            LspDirectoryService lspDirectoryService,
            UserAdminService userAdminService,
            ProductConfigurationService productConfigurationService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationServicingReadService loanApplicationServicingReadService,
            LoanDisbursementCommandService loanDisbursementCommandService,
            LoanRepaymentCommandService loanRepaymentCommandService,
            LoanForeclosureCommandService loanForeclosureCommandService,
            AppUserRepository appUserRepository,
            LspRepository lspRepository,
            LoanProductRepository loanProductRepository,
            LoanApplicationRepository loanApplicationRepository,
            JdbcTemplate jdbcTemplate,
            SecurityProperties securityProperties,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor
    ) {
        this.lspDirectoryService = lspDirectoryService;
        this.userAdminService = userAdminService;
        this.productConfigurationService = productConfigurationService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationServicingReadService = loanApplicationServicingReadService;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.loanRepaymentCommandService = loanRepaymentCommandService;
        this.loanForeclosureCommandService = loanForeclosureCommandService;
        this.appUserRepository = appUserRepository;
        this.lspRepository = lspRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.securityProperties = securityProperties;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
    }

    public void seedDemoPortfolio() {
        // Two scopes on purpose, and the split is what keeps startup from deadlocking.
        //
        // The reset truncates business tables and must COMMIT before anything else runs. The
        // services called below open REQUIRES_NEW transactions of their own; while the truncate
        // was still open in an enclosing transaction those children blocked forever on its table
        // locks, so the application never reported ready.
        resetBusinessDataWithRetry();

        // The remaining steps still write across tenants, so they need admin data-access scope —
        // but each service call must own its transaction. runAsAdmin sets the scope without
        // opening one, so no parent transaction holds locks while those children commit.
        TenantScopedExecution.runAsAdmin(() -> {
            Lsp lsp = ensureLsp();
            LoanProduct product = ensureProduct(lsp);
            seedUsers(lsp.getId());
            seedLoans(lsp, product);
        });
    }

    /**
     * Scheduled workers begin polling when the context refreshes, which is before this
     * ApplicationRunner executes. PortfolioKpiSnapshotWorker in particular scans every LSP on its
     * first tick. That reader and this TRUNCATE take locks on the same tables in opposite orders,
     * so PostgreSQL detects a genuine deadlock and aborts one side — observed as
     * "deadlock detected" on the TRUNCATE during a cold start.
     *
     * <p>PostgreSQL guarantees the surviving side makes progress, so a bounded retry lets the
     * seeder win a later round instead of failing startup. The retry belongs here rather than
     * inside {@link #resetBusinessData()} because a deadlock aborts the whole transaction.
     */
    private void resetBusinessDataWithRetry() {
        for (int attempt = 1; ; attempt++) {
            try {
                adminScopedTransactionExecutor.run(this::resetBusinessData);
                return;
            } catch (PessimisticLockingFailureException exception) {
                if (attempt >= RESET_MAX_ATTEMPTS) {
                    throw exception;
                }
                log.warn(
                        "demo_seed_reset_lock_conflict attempt={} of {}; retrying",
                        attempt,
                        RESET_MAX_ATTEMPTS
                );
                try {
                    Thread.sleep(RESET_RETRY_BACKOFF_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    private void resetBusinessData() {
        jdbcTemplate.execute("DELETE FROM app_user_role");
        jdbcTemplate.execute("DELETE FROM app_user");
        jdbcTemplate.execute("TRUNCATE TABLE report_request, borrower, loan_product, lsp RESTART IDENTITY CASCADE");
    }

    private Lsp ensureLsp() {
        return lspRepository.findByCodeIgnoreCase(DEMO_LSP_CODE)
                .orElseGet(() -> lspDirectoryService.createLsp(DEMO_LSP_CODE, "Supa One Finance", LspStatus.ACTIVE));
    }

    private LoanProduct ensureProduct(Lsp lsp) {
        LoanProduct product = loanProductRepository.findByCodeIgnoreCase(DEMO_PRODUCT_CODE)
                .orElseGet(() -> productConfigurationService.createProduct(
                        DEMO_PRODUCT_CODE,
                        "Supa Flex Cash",
                        new BigDecimal("25000.00"),
                        new BigDecimal("600000.00"),
                        new BigDecimal("19.50"),
                        new BigDecimal("2.00"),
                        6,
                        24,
                        LoanProductStatus.ACTIVE
                ));
        productConfigurationService.replaceProductMappings(product.getId(), Set.of(lsp.getId()));
        return product;
    }

    private void seedUsers(UUID lspId) {
        createUserIfMissing(
                "ops.admin",
                "ops.admin@bhawana.local",
                securityProperties.getBootstrapUser().getPassword(),
                UserStatus.ACTIVE,
                null,
                Set.of(RoleCode.SYSTEM_ADMIN, RoleCode.OPS_USER)
        );
        createUserIfMissing("ops.supervisor", "ops.supervisor@bhawana.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, null, Set.of(RoleCode.SYSTEM_ADMIN));
        createUserIfMissing("ops.reviewer1", "ops.reviewer1@bhawana.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, null, Set.of(RoleCode.OPS_USER));
        createUserIfMissing("ops.reviewer2", "ops.reviewer2@bhawana.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, null, Set.of(RoleCode.OPS_USER));
        createUserIfMissing("ops.risk", "ops.risk@bhawana.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, null, Set.of(RoleCode.OPS_USER));
        createUserIfMissing("product.owner", "product.owner@bhawana.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, null, Set.of(RoleCode.PRODUCT_ADMIN));
        createUserIfMissing("lsp.read1", "lsp.read1@supaone.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, lspId, Set.of(RoleCode.LSP_UI_READ));
        createUserIfMissing("lsp.read2", "lsp.read2@supaone.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, lspId, Set.of(RoleCode.LSP_UI_READ));
        createUserIfMissing("lsp.write1", "lsp.write1@supaone.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, lspId, Set.of(RoleCode.LSP_UI_WRITE));
        createUserIfMissing("lsp.write2", "lsp.write2@supaone.local", DEFAULT_USER_PASSWORD, UserStatus.ACTIVE, lspId, Set.of(RoleCode.LSP_UI_WRITE));
    }

    private void createUserIfMissing(
            String username,
            String email,
            String password,
            UserStatus status,
            UUID lspId,
            Set<RoleCode> roleCodes
    ) {
        if (appUserRepository.existsByUsername(username)) {
            return;
        }
        userAdminService.createUser(username, email, password, status, lspId, roleCodes);
    }

    private void seedLoans(Lsp lsp, LoanProduct product) {
        createReceivedLoan(lsp, product, "SUPA-1001", "Aman Verma", "ABCDE1001F", "9000001001", "aman.verma@demo.local");
        createUnderReviewLoan(lsp, product, "SUPA-1002", "Bhavna Rao", "ABCDE1002F", "9000001002", "bhavna.rao@demo.local");
        createHoldLoan(lsp, product, "SUPA-1003", "Chetan Shah", "ABCDE1003F", "9000001003", "chetan.shah@demo.local");
        createRejectedLoan(lsp, product, "SUPA-1004", "Divya Nair", "ABCDE1004F", "9000001004", "divya.nair@demo.local");
        createApprovedPendingDisbursementLoan(lsp, product, "SUPA-1005", "Eshan Gupta", "ABCDE1005F", "9000001005", "eshan.gupta@demo.local");
        createDisbursementRequestedLoan(lsp, product, "SUPA-1006", "Farah Khan", "ABCDE1006F", "9000001006", "farah.khan@demo.local");
        createDisbursedLoan(lsp, product, "SUPA-1007", "Gautam Iyer", "ABCDE1007F", "9000001007", "gautam.iyer@demo.local");
        createDisbursementFailedLoan(lsp, product, "SUPA-1008", "Heena Das", "ABCDE1008F", "9000001008", "heena.das@demo.local");
        createClosedLoan(lsp, product, "SUPA-1009", "Ishaan Mehta", "ABCDE1009F", "9000001009", "ishaan.mehta@demo.local");
        createForeclosedLoan(lsp, product, "SUPA-1010", "Juhi Sen", "ABCDE1010F", "9000001010", "juhi.sen@demo.local");
    }

    private void createReceivedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("85000.00"), 12);
    }

    private void createUnderReviewLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("120000.00"), 12);
        application = loanApplicationLifecycleService.transitionStatus(
                application.getId(),
                "ops.reviewer1",
                LoanApplicationStatus.AWAITING_APPROVAL,
                "Picked up for review",
                null
        );
    }

    private void createHoldLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("175000.00"), 18);
        application = loanApplicationLifecycleService.transitionStatus(
                application.getId(),
                "ops.reviewer2",
                LoanApplicationStatus.AWAITING_APPROVAL,
                "Started verification",
                null
        );
    }

    private void createRejectedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("230000.00"), 18);
        application = loanApplicationLifecycleService.transitionStatus(
                application.getId(),
                "ops.risk",
                LoanApplicationStatus.AWAITING_APPROVAL,
                "Underwriting review started",
                null
        );
        loanApplicationLifecycleService.transitionStatus(
                application.getId(),
                "ops.risk",
                LoanApplicationStatus.REJECTED,
                "Verification mismatch on borrower profile",
                LoanApplicationStatusReasonCode.FAILED_VERIFICATION
        );
    }

    private void createApprovedPendingDisbursementLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("260000.00"), 24);
        moveToApproved(application.getId(), "ops.reviewer1");
    }

    private void createDisbursementRequestedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("145000.00"), 12);
        moveToApproved(application.getId(), "ops.reviewer1");
        loanDisbursementCommandService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
    }

    private void createDisbursedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("98000.00"), 12);
        moveToApproved(application.getId(), "ops.reviewer2");
        loanDisbursementCommandService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanDisbursementCommandService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.DISBURSED);
        var firstInstallment = loanApplicationServicingReadService.listRepaymentSchedule(application.getId()).stream()
                .findFirst()
                .orElseThrow();
        loanRepaymentCommandService.recordPaymentTransactionWithRecovery(
                application.getId(),
                INTERNAL_ACTOR,
                UUID.randomUUID().toString(),
                firstInstallment.getId(),
                firstInstallment.getOutstandingAmount(),
                LocalDate.now(),
                "PAY-SUPA-1007",
                LoanPaymentChannel.UPI
        );
    }

    private void createDisbursementFailedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("305000.00"), 24);
        moveToApproved(application.getId(), "ops.risk");
        loanDisbursementCommandService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanDisbursementCommandService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.FAILED);
    }

    private void createClosedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("112000.00"), 12);
        moveToApproved(application.getId(), "ops.reviewer1");
        loanDisbursementCommandService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanDisbursementCommandService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.DISBURSED);
        for (var installment : loanApplicationServicingReadService.listRepaymentSchedule(application.getId())) {
            loanRepaymentCommandService.recordPaymentTransactionWithRecovery(
                    application.getId(),
                    INTERNAL_ACTOR,
                    UUID.randomUUID().toString(),
                    installment.getId(),
                    installment.getOutstandingAmount(),
                    LocalDate.now(),
                    "PAY-SUPA-1009-" + installment.getInstallmentNumber(),
                    LoanPaymentChannel.BANK_TRANSFER
            );
        }
    }

    private void createForeclosedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("390000.00"), 24);
        moveToApproved(application.getId(), "ops.risk");
        loanDisbursementCommandService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanDisbursementCommandService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.DISBURSED);
        LocalDate settlementDate = LocalDate.now();
        var quote = loanForeclosureCommandService.requestForeclosureQuote(application.getId(), INTERNAL_ACTOR, settlementDate);
        loanForeclosureCommandService.executeForeclosureQuote(
                application.getId(),
                quote.getId(),
                INTERNAL_ACTOR,
                settlementDate,
                "FORECLOSE-SUPA-1010",
                "Demo foreclosure settlement"
        );
    }

    private LoanApplication createBaseLoan(
            Lsp lsp,
            LoanProduct product,
            String externalId,
            String name,
            String pan,
            String mobile,
            String email,
            BigDecimal amount,
            int tenureMonths
    ) {
        return loanApplicationRepository.findByLsp_IdAndExternalLoanIdIgnoreCase(lsp.getId(), externalId)
                .orElseGet(() -> loanApplicationLifecycleService.createApplication(
                        INTERNAL_ACTOR,
                        new LoanApplicationOnboardingCommand(
                                lsp.getId(),
                                product.getId(),
                                null,
                                externalId,
                                "API",
                                amount,
                                product.getInterestRate(),
                                tenureMonths,
                                BorrowerProfile.builder()
                                        .fullName(name)
                                        .emailAddress(email)
                                        .mobileNumber(mobile)
                                        .dateOfBirth(LocalDate.of(1990, 1, 1))
                                        .gender("FEMALE")
                                        .maritalStatus("SINGLE")
                                        .fatherName("Demo Parent")
                                        .aadharNumber("123412341234")
                                        .panNumber(pan)
                                        .addressLine1("Demo Address Line 1")
                                        .addressLine2("Demo Address Line 2")
                                        .addressCity("Bengaluru")
                                        .addressState("Karnataka")
                                        .addressZipcode("560001")
                                        .employmentStatus("SALARIED")
                                        .organizationName("Demo Employer")
                                        .empId("EMP-" + externalId)
                                        .employmentCity("Bengaluru")
                                        .employmentState("Karnataka")
                                        .employmentZip("560001")
                                        .monthlyIncome(new BigDecimal("85000.00"))
                                        .annualIncome(new BigDecimal("1020000.00"))
                                        .bankAccountNumber("123456789012")
                                        .bankName("Demo Bank")
                                        .ifscCode("HDFC0001234")
                                        .accountHolderName(name)
                                        .referencePersonName("Demo Reference")
                                        .referencePersonNumber("9898989898")
                                        .build()
                        )
                ));
    }

    private boolean loanExists(UUID lspId, String externalId) {
        return loanApplicationRepository.findByLsp_IdAndExternalLoanIdIgnoreCase(lspId, externalId).isPresent();
    }

    private void moveToApproved(UUID applicationId, String actorUsername) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        if (application.getStatus() == LoanApplicationStatus.INITIALIZED) {
            loanApplicationLifecycleService.transitionStatus(
                    applicationId,
                    actorUsername,
                    LoanApplicationStatus.AWAITING_APPROVAL,
                    "Review started",
                    null
            );
        }
        // Every intake-required document must be submitted before approval, not just the six
        // KYC ones. validateKycCompletionBeforeApproval filters on
        // LoanApplicationDocumentRequirements.isIntakeRequired, which also covers KFS and the
        // loan agreement. Submitting those two after the transition left approval permanently
        // blocked on documents the seed had not uploaded yet.
        submitRequiredDocuments(applicationId, actorUsername);

        // Completing the checklist can trigger auto-approval, so re-read the status rather than
        // assuming the application is still awaiting a manual transition.
        application = loanApplicationQueryService.getApplication(applicationId);
        if (application.getStatus() == LoanApplicationStatus.AWAITING_APPROVAL) {
            loanApplicationLifecycleService.transitionStatus(
                    applicationId,
                    actorUsername,
                    LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                    "Approved in demo seed",
                    null
            );
        }
    }

    /**
     * Submits every document the approval gate requires, derived from the same predicate that
     * gate uses. Two hand-maintained lists previously drifted from the rule and blocked the seed.
     */
    private void submitRequiredDocuments(UUID applicationId, String actorUsername) {
        for (LoanApplicationDocumentType documentType : LoanApplicationDocumentType.values()) {
            if (!LoanApplicationDocumentRequirements.isIntakeRequired(documentType)) {
                continue;
            }
            loanApplicationLifecycleService.updateDocumentChecklistItem(
                    applicationId,
                    documentType,
                    actorUsername,
                    LoanApplicationDocumentChecklistStatus.SUBMITTED,
                    "Uploaded during demo seed",
                    documentType.name().toLowerCase() + ".pdf",
                    "seed://" + documentType.name().toLowerCase(),
                    "seed",
                    "application/pdf",
                    null,
                    null,
                    null,
                    false
            );
        }
    }
}
