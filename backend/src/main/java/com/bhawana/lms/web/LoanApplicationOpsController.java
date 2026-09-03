package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.common.api.PagedResult;
import com.bhawana.lms.common.api.PaginationResponseBuilder;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.service.LoanApplicationDetailAssembler;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanApplicationOnboardingCommand;
import com.bhawana.lms.service.LoanApplicationQueryService;
import com.bhawana.lms.service.LoanApplicationServicingReadService;
import com.bhawana.lms.service.AdminApiIdempotencyService;
import com.bhawana.lms.service.DisbursementPreviewService;
import com.bhawana.lms.service.DisbursementReferenceService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanRepaymentCommandService;
import com.bhawana.lms.service.LoanDocumentService;
import com.bhawana.lms.service.LoanForeclosureCommandService;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationAuditEventResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationDetailResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationDocumentAccessAuditResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationDocumentChecklistResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationIntakeAuditResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationRequest;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationStatusTransitionRequest;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanApplicationStatusTransitionResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanDisbursementRequestResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.DisbursementPreviewResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.DisbursementReferenceResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanForeclosureExecutionRequest;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanForeclosureQuoteRequest;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanForeclosureQuoteResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanPaymentTransactionRequest;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanPaymentTransactionResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.LoanRepaymentScheduleInstallmentResponse;
import com.bhawana.lms.web.LoanApplicationOpsApiTypes.ManualStatusUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ops/loan-applications")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
public class LoanApplicationOpsController {

    private static final String OPS_STATUS_TRANSITION = "OPS_STATUS_TRANSITION";
    private static final String OPS_MANUAL_STATUS_OVERRIDE = "OPS_MANUAL_STATUS_OVERRIDE";
    private static final String OPS_DISBURSEMENT_INITIATE = "OPS_DISBURSEMENT_INITIATE";
    private static final String OPS_DISBURSEMENT_MOCK_OUTCOME = "OPS_DISBURSEMENT_MOCK_OUTCOME";
    private static final String OPS_DISBURSEMENT_STATUS_CHECK = "OPS_DISBURSEMENT_STATUS_CHECK";
    private static final String OPS_FORECLOSURE_EXECUTE = "OPS_FORECLOSURE_EXECUTE";

    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final LoanForeclosureCommandService loanForeclosureCommandService;
    private final LoanApplicationServicingReadService loanApplicationServicingReadService;
    private final LoanDisbursementCommandService loanDisbursementCommandService;
    private final DisbursementPreviewService disbursementPreviewService;
    private final DisbursementReferenceService disbursementReferenceService;
    private final LoanRepaymentCommandService loanRepaymentCommandService;
    private final LoanApplicationDetailAssembler loanApplicationDetailAssembler;
    private final BusinessCalendar businessCalendar;
    private final AdminApiIdempotencyService adminApiIdempotencyService;

    public LoanApplicationOpsController(
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            LoanForeclosureCommandService loanForeclosureCommandService,
            LoanApplicationServicingReadService loanApplicationServicingReadService,
            LoanDisbursementCommandService loanDisbursementCommandService,
            DisbursementPreviewService disbursementPreviewService,
            DisbursementReferenceService disbursementReferenceService,
            LoanRepaymentCommandService loanRepaymentCommandService,
            LoanApplicationDetailAssembler loanApplicationDetailAssembler,
            BusinessCalendar businessCalendar,
            AdminApiIdempotencyService adminApiIdempotencyService
    ) {
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.loanForeclosureCommandService = loanForeclosureCommandService;
        this.loanApplicationServicingReadService = loanApplicationServicingReadService;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.disbursementPreviewService = disbursementPreviewService;
        this.disbursementReferenceService = disbursementReferenceService;
        this.loanRepaymentCommandService = loanRepaymentCommandService;
        this.loanApplicationDetailAssembler = loanApplicationDetailAssembler;
        this.businessCalendar = businessCalendar;
        this.adminApiIdempotencyService = adminApiIdempotencyService;
    }

    @GetMapping
    public ResponseEntity<List<LoanApplicationResponse>> listApplications(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceChannel,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String lspLoanId,
            @RequestParam(required = false) String bhawLoanId,
            @RequestParam(required = false) LocalDate disbursalDateFrom,
            @RequestParam(required = false) LocalDate disbursalDateTo,
            @RequestParam(required = false) @Min(0) Integer offset,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            @RequestParam(required = false) String paginationDetails
    ) {
        boolean includePaginationDetails = PaginationResponseBuilder.includePaginationDetails(paginationDetails);
        PagedResult<LoanApplication> applicationsPage = loanApplicationQueryService.listApplicationsPage(
                lspId,
                productId,
                status,
                sourceChannel,
                query,
                lspLoanId,
                bhawLoanId,
                disbursalDateFrom,
                disbursalDateTo,
                offset,
                limit,
                includePaginationDetails
        );
        Map<UUID, String> accountNumbersByApplicationId = loanApplicationServicingReadService
                .getLoanAccountNumbers(applicationsPage.items().stream().map(LoanApplication::getId).toList());
        PagedResult<LoanApplicationResponse> page = new PagedResult<>(
                applicationsPage.items().stream()
                .map(application -> LoanApplicationOpsResponses.toResponse(
                        application,
                        accountNumbersByApplicationId.get(application.getId())
                ))
                .toList(),
                applicationsPage.totalCount(),
                applicationsPage.offset(),
                applicationsPage.limit()
        );
        return PaginationResponseBuilder.toListResponse(page, includePaginationDetails);
    }

    @GetMapping("/{applicationId}")
    public LoanApplicationDetailResponse getApplication(@PathVariable UUID applicationId) {
        return LoanApplicationOpsResponses.toDetailResponse(loanApplicationDetailAssembler.getDetail(applicationId));
    }

    @GetMapping("/{applicationId}/intake-audits")
    public List<LoanApplicationIntakeAuditResponse> listIntakeAudits(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        return loanApplicationServicingReadService.listIntakeAudits(applicationId, authentication.getName()).stream()
                .map(LoanApplicationOpsResponses::toAuditResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/status-transitions")
    public List<LoanApplicationStatusTransitionResponse> listStatusTransitions(@PathVariable UUID applicationId) {
        return loanApplicationServicingReadService.listStatusTransitions(applicationId).stream()
                .map(LoanApplicationOpsResponses::toTransitionResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/audit-events")
    public List<LoanApplicationAuditEventResponse> listAuditEvents(@PathVariable UUID applicationId) {
        return loanApplicationServicingReadService.listAuditEvents(applicationId).stream()
                .map(LoanApplicationOpsResponses::toAuditEventResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/document-access-audits")
    public List<LoanApplicationDocumentAccessAuditResponse> listDocumentAccessAudits(@PathVariable UUID applicationId) {
        return loanApplicationServicingReadService.listDocumentAccessAudits(applicationId).stream()
                .map(LoanApplicationOpsResponses::toDocumentAccessAuditResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/disbursement-preview")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public DisbursementPreviewResponse getDisbursementPreview(@PathVariable UUID applicationId) {
        return LoanApplicationOpsResponses.toDisbursementPreviewResponse(
                disbursementPreviewService.buildPreview(applicationId)
        );
    }

    /**
     * Resolves the durable provider reference after intent create (Tx-A) or from the
     * request log — available even when the provider call has not yet run.
     */
    @GetMapping("/{applicationId}/disbursement-reference")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DisbursementReferenceResponse> getDisbursementReference(
            @PathVariable UUID applicationId
    ) {
        return disbursementReferenceService.resolve(applicationId)
                .map(LoanApplicationOpsResponses::toDisbursementReferenceResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{applicationId}/disbursement-requests")
    public List<LoanDisbursementRequestResponse> listDisbursementRequests(@PathVariable UUID applicationId) {
        return loanApplicationServicingReadService.listDisbursementRequests(applicationId).stream()
                .map(LoanApplicationOpsResponses::toDisbursementRequestResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/repayment-schedule")
    public List<LoanRepaymentScheduleInstallmentResponse> listRepaymentSchedule(@PathVariable UUID applicationId) {
        LocalDate businessDate = businessCalendar.today();
        return loanApplicationServicingReadService.listRepaymentSchedule(applicationId).stream()
                .map(installment -> LoanApplicationOpsResponses.toRepaymentScheduleInstallmentResponse(
                        installment,
                        businessDate
                ))
                .toList();
    }

    @GetMapping("/{applicationId}/payments")
    public List<LoanPaymentTransactionResponse> listPaymentTransactions(@PathVariable UUID applicationId) {
        return loanApplicationServicingReadService.listPaymentTransactions(applicationId).stream()
                .map(LoanApplicationOpsResponses::toPaymentTransactionResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/foreclosure-quotes")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public List<LoanForeclosureQuoteResponse> listForeclosureQuotes(@PathVariable UUID applicationId) {
        return loanApplicationServicingReadService.listForeclosureQuotes(applicationId).stream()
                .map(LoanApplicationOpsResponses::toForeclosureQuoteResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/kyc-documents")
    public List<LoanApplicationDocumentChecklistResponse> listDocumentChecklist(
            Authentication authentication,
            @PathVariable UUID applicationId
    ) {
        return loanApplicationServicingReadService.listDocumentChecklist(applicationId, authentication.getName()).stream()
                .map(LoanApplicationOpsResponses::toDocumentChecklistResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/kyc-documents/download-all")
    public ResponseEntity<byte[]> downloadAllDocuments(
            Authentication authentication,
            HttpServletRequest request,
            @PathVariable UUID applicationId
    ) {
        LoanDocumentService.ZipBuildResult zipResult = loanApplicationServicingReadService.downloadDocumentZip(
                applicationId,
                authentication.getName(),
                ClientIpAddresses.resolve(request),
                CorrelationIdHolder.get()
        );
        byte[] zipContent = zipResult.content();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"loan-" + applicationId + "-documents.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zipContent.length)
                .body(zipContent);
    }

    /**
     * Stream a single stored document either as a download ({@code disposition=attachment},
     * the default) or for safe in-browser preview ({@code disposition=inline}). Inline is
     * restricted server-side to the preview allowlist (PDF/JPEG/PNG) and rejected with 415
     * otherwise. Content is always served {@code Cache-Control: no-store} (KYC PII) and the
     * matching access-audit row (PREVIEWED vs DOWNLOADED) is written by the read service.
     */
    @GetMapping("/{applicationId}/kyc-documents/{documentType}/content")
    public ResponseEntity<Resource> getDocumentContent(
            Authentication authentication,
            HttpServletRequest request,
            @PathVariable UUID applicationId,
            @PathVariable LoanApplicationDocumentType documentType,
            @RequestParam(name = "disposition", defaultValue = "attachment") String disposition
    ) {
        boolean inline = "inline".equalsIgnoreCase(disposition);
        LoanDocumentService.StreamedDocumentContent content = loanApplicationServicingReadService.accessDocumentContent(
                applicationId,
                documentType,
                inline,
                authentication.getName(),
                ClientIpAddresses.resolve(request),
                CorrelationIdHolder.get()
        );
        ContentDisposition contentDisposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(content.fileName())
                .build();
        // Stream the bytes straight from storage (closed by the MVC layer after the
        // body is written) instead of buffering the whole document into heap.
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.contentLength())
                .body(new KnownLengthInputStreamResource(content.content(), content.contentLength()));
    }

    /**
     * {@link InputStreamResource} whose {@link #contentLength()} returns the known
     * size instead of draining the stream to count bytes (the default behaviour,
     * which would consume the single-shot storage stream before it is written).
     */
    private static final class KnownLengthInputStreamResource extends InputStreamResource {

        private final long contentLength;

        private KnownLengthInputStreamResource(java.io.InputStream inputStream, long contentLength) {
            super(inputStream);
            this.contentLength = contentLength;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }
    }

    @PostMapping
    public LoanApplicationResponse createApplication(
            Authentication authentication,
            @Valid @RequestBody LoanApplicationRequest request
    ) {
        LoanApplication application = loanApplicationLifecycleService.createApplication(
                authentication.getName(),
                new LoanApplicationOnboardingCommand(
                        request.lspId(),
                        request.productId(),
                        null,
                        request.externalLoanId(),
                        request.sourceChannel(),
                        request.requestedAmount(),
                        null,
                        request.tenureMonths(),
                        BorrowerProfileMappers.fromOps(request)
                )
        );
        return LoanApplicationOpsResponses.toResponse(application);
    }

    @PostMapping("/{applicationId}/status-transitions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse transitionStatus(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LoanApplicationStatusTransitionRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doTransitionStatus(authentication, applicationId, request);
        }
        return adminApiIdempotencyService.execute(
                OPS_STATUS_TRANSITION,
                idempotencyKey,
                new StatusTransitionFingerprint(applicationId.toString(), request),
                LoanApplicationDetailResponse.class,
                () -> doTransitionStatus(authentication, applicationId, request)
        );
    }

    private LoanApplicationDetailResponse doTransitionStatus(
            Authentication authentication,
            UUID applicationId,
            LoanApplicationStatusTransitionRequest request
    ) {
        loanApplicationLifecycleService.transitionStatus(
                applicationId,
                authentication.getName(),
                request.targetStatus(),
                request.note(),
                request.reasonCode()
        );
        return LoanApplicationOpsResponses.toDetailResponse(loanApplicationDetailAssembler.getDetail(applicationId));
    }

    @PostMapping("/{applicationId}/manual-status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse manuallyOverrideStatus(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ManualStatusUpdateRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doManualStatusOverride(authentication, applicationId, request);
        }
        return adminApiIdempotencyService.execute(
                OPS_MANUAL_STATUS_OVERRIDE,
                idempotencyKey,
                new ManualStatusOverrideFingerprint(applicationId.toString(), request),
                LoanApplicationDetailResponse.class,
                () -> doManualStatusOverride(authentication, applicationId, request)
        );
    }

    private LoanApplicationDetailResponse doManualStatusOverride(
            Authentication authentication,
            UUID applicationId,
            ManualStatusUpdateRequest request
    ) {
        loanApplicationLifecycleService.manuallyOverrideStatus(
                applicationId,
                authentication.getName(),
                request.targetStatus(),
                request.note(),
                request.reasonCode()
        );
        return LoanApplicationOpsResponses.toDetailResponse(loanApplicationDetailAssembler.getDetail(applicationId));
    }

    @PostMapping("/{applicationId}/disbursement-requests")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse initiateDisbursement(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doInitiateDisbursement(authentication, applicationId);
        }
        return adminApiIdempotencyService.execute(
                OPS_DISBURSEMENT_INITIATE,
                idempotencyKey,
                Map.of("applicationId", applicationId.toString()),
                LoanApplicationDetailResponse.class,
                () -> doInitiateDisbursement(authentication, applicationId)
        );
    }

    private LoanApplicationDetailResponse doInitiateDisbursement(
            Authentication authentication,
            UUID applicationId
    ) {
        loanDisbursementCommandService.initiateDisbursement(
                applicationId,
                authentication.getName()
        );
        return LoanApplicationOpsResponses.toDetailResponse(loanApplicationDetailAssembler.getDetail(applicationId));
    }

    @PostMapping("/{applicationId}/disbursement-requests/mock-outcome")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse applyMockDisbursementOutcome(
            Authentication authentication,
            HttpServletRequest httpRequest,
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MockDisbursementOutcomeRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doApplyMockDisbursementOutcome(authentication, httpRequest, applicationId, request);
        }
        return adminApiIdempotencyService.execute(
                OPS_DISBURSEMENT_MOCK_OUTCOME,
                idempotencyKey,
                new MockDisbursementOutcomeFingerprint(applicationId.toString(), request),
                LoanApplicationDetailResponse.class,
                () -> doApplyMockDisbursementOutcome(authentication, httpRequest, applicationId, request)
        );
    }

    private LoanApplicationDetailResponse doApplyMockDisbursementOutcome(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID applicationId,
            MockDisbursementOutcomeRequest request
    ) {
        loanDisbursementCommandService.resolveMockDisbursementOutcome(
                applicationId,
                authentication.getName(),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get(),
                request.outcome()
        );
        return LoanApplicationOpsResponses.toDetailResponse(loanApplicationDetailAssembler.getDetail(applicationId));
    }

    @PostMapping("/{applicationId}/disbursement-requests/status-check")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanApplicationDetailResponse runDisbursementStatusCheck(
            Authentication authentication,
            HttpServletRequest httpRequest,
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doRunDisbursementStatusCheck(authentication, httpRequest, applicationId);
        }
        return adminApiIdempotencyService.execute(
                OPS_DISBURSEMENT_STATUS_CHECK,
                idempotencyKey,
                Map.of("applicationId", applicationId.toString()),
                LoanApplicationDetailResponse.class,
                () -> doRunDisbursementStatusCheck(authentication, httpRequest, applicationId)
        );
    }

    private LoanApplicationDetailResponse doRunDisbursementStatusCheck(
            Authentication authentication,
            HttpServletRequest httpRequest,
            UUID applicationId
    ) {
        loanDisbursementCommandService.pollPendingDisbursement(
                applicationId,
                authentication.getName(),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );
        return LoanApplicationOpsResponses.toDetailResponse(loanApplicationDetailAssembler.getDetail(applicationId));
    }

    @PostMapping("/{applicationId}/payments")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanPaymentTransactionResponse recordPaymentTransaction(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LoanPaymentTransactionRequest request
    ) {
        return LoanApplicationOpsResponses.toPaymentTransactionResponse(loanRepaymentCommandService.recordPaymentTransactionWithRecovery(
                applicationId,
                authentication.getName(),
                idempotencyKey,
                request.targetInstallmentId(),
                request.amount(),
                request.postedAt(),
                request.reference(),
                request.channel()
        ));
    }

    @PostMapping("/{applicationId}/foreclosure-quotes")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanForeclosureQuoteResponse requestForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanForeclosureQuoteRequest request
    ) {
        return LoanApplicationOpsResponses.toForeclosureQuoteResponse(loanForeclosureCommandService.requestForeclosureQuote(
                applicationId,
                authentication.getName(),
                request.effectiveDate()
        ));
    }

    @PostMapping("/{applicationId}/foreclosure-quotes/{quoteId}/execute")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public LoanForeclosureQuoteResponse executeForeclosureQuote(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @PathVariable UUID quoteId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LoanForeclosureExecutionRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doExecuteForeclosureQuote(authentication, applicationId, quoteId, request);
        }
        return adminApiIdempotencyService.execute(
                OPS_FORECLOSURE_EXECUTE,
                idempotencyKey,
                new ForeclosureExecuteFingerprint(applicationId.toString(), quoteId.toString(), request),
                LoanForeclosureQuoteResponse.class,
                () -> doExecuteForeclosureQuote(authentication, applicationId, quoteId, request)
        );
    }

    private LoanForeclosureQuoteResponse doExecuteForeclosureQuote(
            Authentication authentication,
            UUID applicationId,
            UUID quoteId,
            LoanForeclosureExecutionRequest request
    ) {
        return LoanApplicationOpsResponses.toForeclosureQuoteResponse(loanForeclosureCommandService.executeForeclosureQuote(
                applicationId,
                quoteId,
                authentication.getName(),
                request.settlementDate(),
                request.reference(),
                request.note()
        ));
    }

    private record StatusTransitionFingerprint(String applicationId, LoanApplicationStatusTransitionRequest request) {
    }

    private record ManualStatusOverrideFingerprint(String applicationId, ManualStatusUpdateRequest request) {
    }

    private record MockDisbursementOutcomeFingerprint(String applicationId, MockDisbursementOutcomeRequest request) {
    }

    private record ForeclosureExecuteFingerprint(
            String applicationId,
            String quoteId,
            LoanForeclosureExecutionRequest request
    ) {
    }
}
