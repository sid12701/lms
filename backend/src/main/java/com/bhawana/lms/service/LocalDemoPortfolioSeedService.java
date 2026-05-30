package com.bhawana.lms.service;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class LocalDemoPortfolioSeedService {

    private static final String DEMO_LSP_CODE = "SUPAONE";
    private static final String DEMO_PRODUCT_CODE = "SUPA-FLEX";
    private static final String DEFAULT_USER_PASSWORD = "DemoPass123!";
    private static final String INTERNAL_ACTOR = "ops.admin";

    private final AdminDirectoryService adminDirectoryService;
    private final ProductConfigurationService productConfigurationService;
    private final LoanApplicationService loanApplicationService;
    private final AppUserRepository appUserRepository;
    private final LspRepository lspRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SecurityProperties securityProperties;

    public LocalDemoPortfolioSeedService(
            AdminDirectoryService adminDirectoryService,
            ProductConfigurationService productConfigurationService,
            LoanApplicationService loanApplicationService,
            AppUserRepository appUserRepository,
            LspRepository lspRepository,
            LoanProductRepository loanProductRepository,
            LoanApplicationRepository loanApplicationRepository,
            JdbcTemplate jdbcTemplate,
            SecurityProperties securityProperties
    ) {
        this.adminDirectoryService = adminDirectoryService;
        this.productConfigurationService = productConfigurationService;
        this.loanApplicationService = loanApplicationService;
        this.appUserRepository = appUserRepository;
        this.lspRepository = lspRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.securityProperties = securityProperties;
    }

    @Transactional
    public void seedDemoPortfolio() {
        resetBusinessData();
        Lsp lsp = ensureLsp();
        LoanProduct product = ensureProduct(lsp);
        seedUsers(lsp.getId());
        seedLoans(lsp, product);
    }

    private void resetBusinessData() {
        jdbcTemplate.execute("DELETE FROM app_user_role");
        jdbcTemplate.execute("DELETE FROM app_user");
        jdbcTemplate.execute("TRUNCATE TABLE report_request, borrower, loan_product, lsp RESTART IDENTITY CASCADE");
    }

    private Lsp ensureLsp() {
        return lspRepository.findByCodeIgnoreCase(DEMO_LSP_CODE)
                .orElseGet(() -> adminDirectoryService.createLsp(DEMO_LSP_CODE, "Supa One Finance", LspStatus.ACTIVE));
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
        adminDirectoryService.createUser(username, email, password, status, lspId, roleCodes);
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
        application = loanApplicationService.transitionStatus(
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
        application = loanApplicationService.transitionStatus(
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
        application = loanApplicationService.transitionStatus(
                application.getId(),
                "ops.risk",
                LoanApplicationStatus.AWAITING_APPROVAL,
                "Underwriting review started",
                null
        );
        loanApplicationService.transitionStatus(
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
        loanApplicationService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
    }

    private void createDisbursedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("98000.00"), 12);
        moveToApproved(application.getId(), "ops.reviewer2");
        loanApplicationService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanApplicationService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.DISBURSED);
        var firstInstallment = loanApplicationService.listRepaymentSchedule(application.getId()).stream()
                .findFirst()
                .orElseThrow();
        loanApplicationService.recordPaymentTransaction(
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
        loanApplicationService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanApplicationService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.FAILED);
    }

    private void createClosedLoan(Lsp lsp, LoanProduct product, String externalId, String name, String pan, String mobile, String email) {
        if (loanExists(lsp.getId(), externalId)) {
            return;
        }
        LoanApplication application = createBaseLoan(lsp, product, externalId, name, pan, mobile, email, new BigDecimal("112000.00"), 12);
        moveToApproved(application.getId(), "ops.reviewer1");
        loanApplicationService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanApplicationService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.DISBURSED);
        for (var installment : loanApplicationService.listRepaymentSchedule(application.getId())) {
            loanApplicationService.recordPaymentTransaction(
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
        loanApplicationService.initiateDisbursement(application.getId(), INTERNAL_ACTOR);
        loanApplicationService.resolveMockDisbursementOutcome(application.getId(), INTERNAL_ACTOR, MockDisbursementOutcome.DISBURSED);
        LocalDate settlementDate = LocalDate.now();
        var quote = loanApplicationService.requestForeclosureQuote(application.getId(), INTERNAL_ACTOR, settlementDate);
        loanApplicationService.executeForeclosureQuote(
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
                .orElseGet(() -> loanApplicationService.createApplication(
                        INTERNAL_ACTOR,
                        new LoanApplicationOnboardingCommand(
                                lsp.getId(),
                                product.getId(),
                                null,
                                externalId,
                                "API",
                                name,
                                email,
                                mobile,
                                LocalDate.of(1990, 1, 1),
                                "FEMALE",
                                "SINGLE",
                                "Demo Parent",
                                "123412341234",
                                pan,
                                amount,
                                product.getInterestRate(),
                                tenureMonths,
                                "Demo Address Line 1",
                                "Demo Address Line 2",
                                "Bengaluru",
                                "Karnataka",
                                "560001",
                                null,
                                "SALARIED",
                                "Demo Employer",
                                "EMP-" + externalId,
                                "Bengaluru",
                                "Karnataka",
                                "560001",
                                new BigDecimal("85000.00"),
                                new BigDecimal("1020000.00"),
                                "123456789012",
                                "Demo Bank",
                                "HDFC0001234",
                                name,
                                "Demo Reference",
                                "9898989898"
                        )
                ));
    }

    private boolean loanExists(UUID lspId, String externalId) {
        return loanApplicationRepository.findByLsp_IdAndExternalLoanIdIgnoreCase(lspId, externalId).isPresent();
    }

    private void moveToApproved(UUID applicationId, String actorUsername) {
        LoanApplication application = loanApplicationService.getApplication(applicationId);
        if (application.getStatus() == LoanApplicationStatus.INITIALIZED) {
            loanApplicationService.transitionStatus(
                    applicationId,
                    actorUsername,
                    LoanApplicationStatus.AWAITING_APPROVAL,
                    "Review started",
                    null
            );
        }
        verifyRequiredDocuments(applicationId, actorUsername);
        application = loanApplicationService.getApplication(applicationId);
        if (application.getStatus() == LoanApplicationStatus.AWAITING_APPROVAL) {
            loanApplicationService.transitionStatus(
                    applicationId,
                    actorUsername,
                    LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                    "Approved in demo seed",
                    null
            );
        }
        uploadRequiredDisbursementDocuments(applicationId, actorUsername);
    }

    private void verifyRequiredDocuments(UUID applicationId, String actorUsername) {
        List<LoanApplicationDocumentType> requiredDocs = List.of(
                LoanApplicationDocumentType.PAN_CARD,
                LoanApplicationDocumentType.AADHAAR_FILE,
                LoanApplicationDocumentType.ADDRESS_PROOF,
                LoanApplicationDocumentType.INCOME_PROOF,
                LoanApplicationDocumentType.BANK_STATEMENT,
                LoanApplicationDocumentType.SELFIE_PHOTOGRAPH
        );
        for (LoanApplicationDocumentType documentType : requiredDocs) {
            loanApplicationService.updateDocumentChecklistItem(
                    applicationId,
                    documentType,
                    actorUsername,
                    LoanApplicationDocumentChecklistStatus.SUBMITTED,
                    "Uploaded during demo seed",
                    documentType.name().toLowerCase() + ".pdf",
                    "seed://" + documentType.name().toLowerCase(),
                    "seed",
                    "application/pdf"
            );
        }
    }

    private void uploadRequiredDisbursementDocuments(UUID applicationId, String actorUsername) {
        for (LoanApplicationDocumentType documentType : List.of(
                LoanApplicationDocumentType.KFS,
                LoanApplicationDocumentType.LOAN_AGREEMENT
        )) {
            loanApplicationService.updateDocumentChecklistItem(
                    applicationId,
                    documentType,
                    actorUsername,
                    LoanApplicationDocumentChecklistStatus.SUBMITTED,
                    "Uploaded during demo seed",
                    documentType.name().toLowerCase() + ".pdf",
                    "seed://" + documentType.name().toLowerCase(),
                    "seed",
                    "application/pdf"
            );
        }
    }
}
