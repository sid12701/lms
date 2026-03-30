package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {

    private final BorrowerRepository borrowerRepository;
    private final LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanProductRepository loanProductRepository;
    private final LspRepository lspRepository;
    private final LoanProductLspMappingRepository loanProductLspMappingRepository;
    private final ObjectMapper objectMapper;

    public LoanApplicationService(
            BorrowerRepository borrowerRepository,
            LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanProductRepository loanProductRepository,
            LspRepository lspRepository,
            LoanProductLspMappingRepository loanProductLspMappingRepository,
            ObjectMapper objectMapper
    ) {
        this.borrowerRepository = borrowerRepository;
        this.loanApplicationIntakeAuditRepository = loanApplicationIntakeAuditRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanProductRepository = loanProductRepository;
        this.lspRepository = lspRepository;
        this.loanProductLspMappingRepository = loanProductLspMappingRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listApplications(
            UUID lspId,
            UUID productId,
            String status,
            String sourceChannel,
            String query
    ) {
        String normalizedQuery = normalizeQuery(query);
        String normalizedStatus = normalizeOptional(status);
        String normalizedSourceChannel = normalizeOptional(sourceChannel);
        return loanApplicationRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(application -> lspId == null || application.getLsp().getId().equals(lspId))
                .filter(application -> productId == null || application.getLoanProduct().getId().equals(productId))
                .filter(application -> normalizedStatus == null
                        || application.getStatus().name().equalsIgnoreCase(normalizedStatus))
                .filter(application -> normalizedSourceChannel == null
                        || application.getSourceChannel().equalsIgnoreCase(normalizedSourceChannel))
                .filter(application -> normalizedQuery == null || matchesQuery(application, normalizedQuery))
                .toList();
    }

    @Transactional
    public LoanApplication createApplication(
            String actorUsername,
            UUID lspId,
            UUID productId,
            String externalLoanId,
            String sourceChannel,
            String borrowerPan,
            String borrowerFullName,
            String borrowerMobile,
            String borrowerEmail,
            BigDecimal requestedAmount,
            int tenureMonths
    ) {
        var lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        if (lsp.getStatus() != LspStatus.ACTIVE) {
            throw new IllegalArgumentException("Loan applications can only be created for active LSPs.");
        }

        var loanProduct = loanProductRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan product id: " + productId));
        if (loanProduct.getStatus() != LoanProductStatus.ACTIVE) {
            throw new IllegalArgumentException("Loan applications can only be created for active loan products.");
        }

        LoanProductLspMapping mapping = loanProductLspMappingRepository.findByLsp_IdAndLoanProduct_Id(lspId, productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Requested product is not mapped to the selected LSP."
                ));
        if (!mapping.isEnabled()) {
            throw new IllegalArgumentException("Requested product mapping is disabled for the selected LSP.");
        }

        String normalizedExternalLoanId = externalLoanId.trim();
        if (loanApplicationRepository.existsByLsp_IdAndExternalLoanIdIgnoreCase(lspId, normalizedExternalLoanId)) {
            throw new IllegalArgumentException("External loan id already exists for the selected LSP.");
        }

        BigDecimal scaledRequestedAmount = requestedAmount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (scaledRequestedAmount.compareTo(loanProduct.getMinPrincipal()) < 0
                || scaledRequestedAmount.compareTo(loanProduct.getMaxPrincipal()) > 0) {
            throw new IllegalArgumentException("Requested amount is outside the configured product principal range.");
        }

        if (tenureMonths < loanProduct.getMinTenureMonths() || tenureMonths > loanProduct.getMaxTenureMonths()) {
            throw new IllegalArgumentException("Requested tenure is outside the configured product tenure range.");
        }

        Borrower borrower = borrowerRepository.findByPanIgnoreCase(normalizePan(borrowerPan))
                .map(existing -> {
                    existing.refreshProfile(
                            borrowerFullName.trim(),
                            borrowerMobile.trim(),
                            normalizeEmail(borrowerEmail)
                    );
                    return borrowerRepository.save(existing);
                })
                .orElseGet(() -> borrowerRepository.save(new Borrower(
                        borrowerFullName.trim(),
                        normalizePan(borrowerPan),
                        borrowerMobile.trim(),
                        normalizeEmail(borrowerEmail)
                )));

        LoanApplication application = new LoanApplication(
                borrower,
                lsp,
                loanProduct,
                normalizedExternalLoanId,
                normalizeSourceChannel(sourceChannel),
                scaledRequestedAmount,
                tenureMonths,
                LoanApplicationStatus.RECEIVED
        );
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        loanApplicationIntakeAuditRepository.save(new LoanApplicationIntakeAudit(
                savedApplication,
                actorUsername,
                CorrelationIdHolder.get(),
                serializePayload(savedApplication)
        ));
        return savedApplication;
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationIntakeAudit> listIntakeAudits(UUID applicationId) {
        if (!loanApplicationRepository.existsById(applicationId)) {
            throw new IllegalArgumentException("Unknown loan application id: " + applicationId);
        }

        return loanApplicationIntakeAuditRepository.findTop10ByLoanApplication_IdOrderByCreatedAtDesc(applicationId);
    }

    private static String normalizePan(String pan) {
        return pan.trim().toUpperCase();
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }

        String normalized = email.trim();
        return normalized.isBlank() ? null : normalized.toLowerCase();
    }

    private static String normalizeSourceChannel(String sourceChannel) {
        return sourceChannel.trim().toUpperCase();
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }

        String normalized = query.trim().toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean matchesQuery(LoanApplication application, String normalizedQuery) {
        return contains(application.getBorrower().getFullName(), normalizedQuery)
                || contains(application.getBorrower().getPan(), normalizedQuery)
                || contains(application.getBorrower().getMobile(), normalizedQuery)
                || contains(application.getExternalLoanId(), normalizedQuery);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase().contains(normalizedQuery);
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
        payload.put("borrowerPan", application.getBorrower().getPan());
        payload.put("borrowerFullName", application.getBorrower().getFullName());
        payload.put("borrowerMobile", application.getBorrower().getMobile());
        payload.put("borrowerEmail", application.getBorrower().getEmail());
        payload.put("status", application.getStatus().name());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize intake payload audit.", exception);
        }
    }
}
