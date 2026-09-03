package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.util.JsonPayloadSerializer;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LoanProductVersion;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.tenant.ScopePreservingTransactionExecutor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LoanApplicationOnboardingService {

    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanProductVersionRepository loanProductVersionRepository;
    private final LspRepository lspRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final BorrowerOnboardingService borrowerOnboardingService;
    private final LoanApplicationDocumentChecklistService documentChecklistService;
    private final LoanEventLog loanEventLog;
    private final ScopePreservingTransactionExecutor scopePreservingTransactionExecutor;
    private final JsonPayloadSerializer jsonPayloadSerializer;

    public LoanApplicationOnboardingService(
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanProductRepository loanProductRepository,
            LoanProductVersionRepository loanProductVersionRepository,
            LspRepository lspRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            BorrowerOnboardingService borrowerOnboardingService,
            LoanApplicationDocumentChecklistService documentChecklistService,
            LoanEventLog loanEventLog,
            ScopePreservingTransactionExecutor scopePreservingTransactionExecutor,
            JsonPayloadSerializer jsonPayloadSerializer
    ) {
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanProductVersionRepository = loanProductVersionRepository;
        this.lspRepository = lspRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.borrowerOnboardingService = borrowerOnboardingService;
        this.documentChecklistService = documentChecklistService;
        this.loanEventLog = loanEventLog;
        this.scopePreservingTransactionExecutor = scopePreservingTransactionExecutor;
        this.jsonPayloadSerializer = jsonPayloadSerializer;
    }

    public LoanApplication createApplication(String actorUsername, LoanApplicationOnboardingCommand command) {
        return createApplication(actorUsername, command, null);
    }

    public LoanApplication createApplication(
            String actorUsername,
            LoanApplicationOnboardingCommand command,
            UUID enforcedLspId
    ) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return doCreateApplication(actorUsername, command, enforcedLspId);
        }
        DataIntegrityViolationException lastRace = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return scopePreservingTransactionExecutor.call(
                        () -> doCreateApplication(actorUsername, command, enforcedLspId));
            } catch (DataIntegrityViolationException borrowerRace) {
                lastRace = borrowerRace;
            }
        }
        throw lastRace;
    }

    private LoanApplication doCreateApplication(
            String actorUsername,
            LoanApplicationOnboardingCommand command,
            UUID enforcedLspId
    ) {
        var lsp = lspRepository.findById(command.lspId())
                .orElseThrow(() -> new ResourceNotFoundException("Unknown LSP id: " + command.lspId()));
        if (enforcedLspId != null && !lsp.getId().equals(enforcedLspId)) {
            throw new AccessDeniedException(
                    "Loan application LSP does not match the authenticated LSP context.");
        }
        if (lsp.getStatus() != LspStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "LSP_NOT_ACTIVE",
                    "Loan applications can only be created for active LSPs.",
                    Map.of("lspId", command.lspId().toString())
            );
        }

        var loanProduct = resolveLoanProduct(command);
        if (loanProduct.getStatus() != LoanProductStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "PRODUCT_NOT_ACTIVE",
                    "Loan applications can only be created for active loan products.",
                    Map.of("productId", loanProduct.getId().toString())
            );
        }

        LoanProductVersion latestVersion = loanProductVersionRepository
                .findTopByLoanProduct_IdOrderByVersionNumberDesc(loanProduct.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Loan product " + loanProduct.getId() + " has no version rows"));

        validateInterestRate(command.interestRate(), latestVersion.getInterestRate());

        LoanProductLspMapping mapping = loanProductLspMappingRepository.findByLsp_IdAndLoanProduct_Id(command.lspId(), loanProduct.getId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "PRODUCT_NOT_MAPPED",
                        "Requested product is not mapped to the selected LSP.",
                        Map.of(
                                "lspId", command.lspId().toString(),
                                "productId", loanProduct.getId().toString()
                        )
                ));
        if (!mapping.isEnabled()) {
            throw new BusinessRuleViolationException(
                    "PRODUCT_MAPPING_DISABLED",
                    "Requested product mapping is disabled for the selected LSP.",
                    Map.of(
                            "lspId", command.lspId().toString(),
                            "productId", loanProduct.getId().toString()
                    )
            );
        }

        String normalizedExternalLoanId = requireField(command.lspLoanId(), "LSP loan id");
        if (loanApplicationRepository.existsByLsp_IdAndExternalLoanIdIgnoreCase(command.lspId(), normalizedExternalLoanId)) {
            throw new ApiConflictException(
                    "DUPLICATE_EXTERNAL_LOAN_ID",
                    "External loan id already exists for the selected LSP."
            );
        }

        BigDecimal scaledRequestedAmount = Money.scale(Money.requirePositive(command.loanAmount(), "Loan amount"));
        if (scaledRequestedAmount.compareTo(loanProduct.getMinPrincipal()) < 0
                || scaledRequestedAmount.compareTo(loanProduct.getMaxPrincipal()) > 0) {
            throw new BusinessRuleViolationException(
                    "AMOUNT_OUT_OF_RANGE",
                    "Requested amount is outside the configured product principal range.",
                    Map.of("loanAmount", scaledRequestedAmount.toPlainString())
            );
        }

        int tenureMonths = requireTenure(command.loanTenure());
        if (tenureMonths < loanProduct.getMinTenureMonths() || tenureMonths > loanProduct.getMaxTenureMonths()) {
            throw new BusinessRuleViolationException(
                    "TENURE_OUT_OF_RANGE",
                    "Requested tenure is outside the configured product tenure range.",
                    Map.of("loanTenure", String.valueOf(tenureMonths))
            );
        }

        BorrowerProfile borrowerProfile = command.borrowerProfile();
        BigDecimal monthlyIncome = normalizeMonthlyIncome(
                borrowerProfile.monthlyIncome(),
                borrowerProfile.annualIncome()
        );
        BigDecimal annualIncome = normalizeAnnualIncome(
                borrowerProfile.monthlyIncome(),
                borrowerProfile.annualIncome()
        );
        Borrower borrower = borrowerOnboardingService.resolveBorrowerForOnboarding(
                lsp,
                command,
                monthlyIncome,
                annualIncome,
                actorUsername
        );
        if (enforcedLspId != null) {
            Map<String, String> missingBorrowerFields = BorrowerOnboardingRequirements.missingRequiredFieldErrors(borrower);
            if (!missingBorrowerFields.isEmpty()) {
                throw new BusinessRuleViolationException(
                        "BORROWER_REQUIRED_FIELDS_MISSING",
                        "Borrower is missing required onboarding fields.",
                        missingBorrowerFields
                );
            }
        }

        LoanApplication application = new LoanApplication(
                borrower,
                lsp,
                loanProduct,
                latestVersion,
                normalizedExternalLoanId,
                normalizeSourceChannel(command.sourceChannel()),
                scaledRequestedAmount,
                tenureMonths,
                LoanApplicationStatus.INITIALIZED
        );
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationIntakeAuditRepository.save(new LoanApplicationIntakeAudit(
                savedApplication,
                actorUsername,
                CorrelationIdHolder.get(),
                serializePayload(savedApplication)
        ));
        documentChecklistService.seedDocumentChecklist(savedApplication, actorUsername);
        loanEventLog.append(
                savedApplication.getLsp(),
                LoanEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                LoanEventPayloads.loanCreated(savedApplication)
        );
        return savedApplication;
    }

    private LoanProduct resolveLoanProduct(LoanApplicationOnboardingCommand command) {
        if (command.productId() != null) {
            return loanProductRepository.findById(command.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Unknown loan product id: " + command.productId()));
        }
        String loanProductCode = Strings.normalizeOptional(command.loanProduct());
        if (loanProductCode == null) {
            throw new IllegalArgumentException("Loan product is required.");
        }
        return loanProductRepository.findByCodeIgnoreCase(loanProductCode)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan product code: " + loanProductCode));
    }

    private String serializePayload(LoanApplication application) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("lspId", application.getLsp().getId());
        payload.put("lspCode", application.getLsp().getCode());
        payload.put("productId", application.getLoanProduct().getId());
        payload.put("productCode", application.getLoanProduct().getCode());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("sourceChannel", application.getSourceChannel());
        payload.put("requestedAmount", application.getRequestedAmount());
        payload.put("tenureMonths", application.getRequestedTenureMonths());
        payload.putAll(BorrowerProfile.fromEntity(application.getBorrower()).intakeAuditEntries());
        return jsonPayloadSerializer.serialize(payload);
    }

    private static String normalizeSourceChannel(String sourceChannel) {
        return sourceChannel.trim().toUpperCase();
    }

    private static void validateInterestRate(BigDecimal requestedInterestRate, BigDecimal configuredInterestRate) {
        if (requestedInterestRate == null) {
            return;
        }
        BigDecimal normalizedRequestedRate = requestedInterestRate.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedConfiguredRate = configuredInterestRate.setScale(2, RoundingMode.HALF_UP);
        if (normalizedRequestedRate.compareTo(normalizedConfiguredRate) != 0) {
            throw new BusinessRuleViolationException(
                    "INTEREST_RATE_MISMATCH",
                    "Requested interest rate does not match the configured product interest rate.",
                    Map.of(
                            "interestRate", normalizedRequestedRate.toPlainString(),
                            "configuredInterestRate", normalizedConfiguredRate.toPlainString()
                    )
            );
        }
    }

    private static BigDecimal normalizeMonthlyIncome(BigDecimal monthlyIncome, BigDecimal annualIncome) {
        if (monthlyIncome != null) {
            return Money.scale(monthlyIncome);
        }
        if (annualIncome == null) {
            return null;
        }
        return Money.scale(annualIncome.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
    }

    private static BigDecimal normalizeAnnualIncome(BigDecimal monthlyIncome, BigDecimal annualIncome) {
        if (annualIncome != null) {
            return Money.scale(annualIncome);
        }
        if (monthlyIncome == null) {
            return null;
        }
        return Money.scale(monthlyIncome.multiply(BigDecimal.valueOf(12)));
    }

    private static int requireTenure(Integer loanTenure) {
        if (loanTenure == null || loanTenure < 1) {
            throw new IllegalArgumentException("Loan tenure must be at least 1 month.");
        }
        return loanTenure;
    }

    private static String requireField(String value, String fieldName) {
        String normalized = Strings.normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return normalized;
    }
}
