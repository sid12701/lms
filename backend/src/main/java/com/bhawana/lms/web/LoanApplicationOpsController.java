package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAssignmentEvent;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationIntakeAudit;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.service.LoanApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ops/loan-applications")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
public class LoanApplicationOpsController {

    private final LoanApplicationService loanApplicationService;

    public LoanApplicationOpsController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @GetMapping
    public List<LoanApplicationResponse> listApplications(
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceChannel,
            @RequestParam(required = false, name = "q") String query
    ) {
        return loanApplicationService.listApplications(lspId, productId, status, sourceChannel, query).stream()
                .map(LoanApplicationOpsController::toResponse)
                .toList();
    }

    @GetMapping("/{applicationId}")
    public LoanApplicationDetailResponse getApplication(@PathVariable UUID applicationId) {
        return toDetailResponse(loanApplicationService.getApplication(applicationId));
    }

    @GetMapping("/{applicationId}/intake-audits")
    public List<LoanApplicationIntakeAuditResponse> listIntakeAudits(@PathVariable UUID applicationId) {
        return loanApplicationService.listIntakeAudits(applicationId).stream()
                .map(LoanApplicationOpsController::toAuditResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/status-transitions")
    public List<LoanApplicationStatusTransitionResponse> listStatusTransitions(@PathVariable UUID applicationId) {
        return loanApplicationService.listStatusTransitions(applicationId).stream()
                .map(LoanApplicationOpsController::toTransitionResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/assignment-events")
    public List<LoanApplicationAssignmentEventResponse> listAssignmentEvents(@PathVariable UUID applicationId) {
        return loanApplicationService.listAssignmentEvents(applicationId).stream()
                .map(LoanApplicationOpsController::toAssignmentEventResponse)
                .toList();
    }

    @GetMapping("/{applicationId}/kyc-documents")
    public List<LoanApplicationDocumentChecklistResponse> listDocumentChecklist(@PathVariable UUID applicationId) {
        return loanApplicationService.listDocumentChecklist(applicationId).stream()
                .map(LoanApplicationOpsController::toDocumentChecklistResponse)
                .toList();
    }

    @PostMapping
    public LoanApplicationResponse createApplication(
            Authentication authentication,
            @Valid @RequestBody LoanApplicationRequest request
    ) {
        LoanApplication application = loanApplicationService.createApplication(
                authentication.getName(),
                request.lspId(),
                request.productId(),
                request.externalLoanId(),
                request.sourceChannel(),
                request.borrowerPan(),
                request.borrowerFullName(),
                request.borrowerMobile(),
                request.borrowerEmail(),
                request.borrowerDateOfBirth(),
                request.borrowerCity(),
                request.borrowerState(),
                request.borrowerEmploymentType(),
                request.borrowerMonthlyIncome(),
                request.requestedAmount(),
                request.tenureMonths()
        );
        return toResponse(application);
    }

    @PostMapping("/{applicationId}/status-transitions")
    public LoanApplicationDetailResponse transitionStatus(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanApplicationStatusTransitionRequest request
    ) {
        LoanApplication application = loanApplicationService.transitionStatus(
                applicationId,
                authentication.getName(),
                request.targetStatus(),
                request.note()
        );
        return toDetailResponse(application);
    }

    @PostMapping("/{applicationId}/assignment")
    public LoanApplicationDetailResponse assignApplication(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanApplicationAssignmentRequest request
    ) {
        LoanApplication application = loanApplicationService.assignApplication(
                applicationId,
                authentication.getName(),
                request.assigneeUsername(),
                request.note()
        );
        return toDetailResponse(application);
    }

    @PutMapping("/{applicationId}/kyc-documents/{documentType}")
    public LoanApplicationDocumentChecklistResponse updateDocumentChecklistItem(
            Authentication authentication,
            @PathVariable UUID applicationId,
            @PathVariable LoanApplicationDocumentType documentType,
            @Valid @RequestBody LoanApplicationDocumentChecklistUpdateRequest request
    ) {
        return toDocumentChecklistResponse(loanApplicationService.updateDocumentChecklistItem(
                applicationId,
                documentType,
                authentication.getName(),
                request.status(),
                request.note(),
                request.fileName(),
                request.fileReference(),
                request.sourceReference(),
                request.contentType()
        ));
    }

    private static LoanApplicationResponse toResponse(LoanApplication application) {
        return new LoanApplicationResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getPan(),
                application.getBorrower().getMobile(),
                application.getBorrower().getEmail(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getMonthlyIncome(),
                application.getLsp().getId().toString(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getExternalLoanId(),
                application.getSourceChannel(),
                application.getRequestedAmount(),
                application.getRequestedTenureMonths(),
                application.getStatus().name(),
                application.getAssignedToUsername(),
                application.getAssignedByUsername(),
                application.getAssignedAt(),
                application.getCreatedAt().toString()
        );
    }

    private static LoanApplicationDetailResponse toDetailResponse(LoanApplication application) {
        return new LoanApplicationDetailResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getPan(),
                application.getBorrower().getMobile(),
                application.getBorrower().getEmail(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getMonthlyIncome(),
                application.getLsp().getId().toString(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getExternalLoanId(),
                application.getSourceChannel(),
                application.getRequestedAmount(),
                application.getRequestedTenureMonths(),
                application.getStatus().name(),
                application.getAssignedToUsername(),
                application.getAssignedByUsername(),
                application.getAssignedAt(),
                application.getCreatedAt().toString(),
                application.getUpdatedAt().toString()
        );
    }

    private static LoanApplicationIntakeAuditResponse toAuditResponse(LoanApplicationIntakeAudit audit) {
        return new LoanApplicationIntakeAuditResponse(
                audit.getId().toString(),
                audit.getLoanApplication().getId().toString(),
                audit.getActorUsername(),
                audit.getCorrelationId(),
                audit.getPayloadJson(),
                audit.getCreatedAt()
        );
    }

    private static LoanApplicationStatusTransitionResponse toTransitionResponse(LoanApplicationStatusTransition transition) {
        return new LoanApplicationStatusTransitionResponse(
                transition.getId().toString(),
                transition.getLoanApplication().getId().toString(),
                transition.getActorUsername(),
                transition.getFromStatus().name(),
                transition.getToStatus().name(),
                transition.getNote(),
                transition.getCorrelationId(),
                transition.getCreatedAt().toString()
        );
    }

    private static LoanApplicationAssignmentEventResponse toAssignmentEventResponse(LoanApplicationAssignmentEvent event) {
        return new LoanApplicationAssignmentEventResponse(
                event.getId().toString(),
                event.getLoanApplication().getId().toString(),
                event.getFromAssigneeUsername(),
                event.getToAssigneeUsername(),
                event.getActorUsername(),
                event.getNote(),
                event.getCorrelationId(),
                event.getCreatedAt().toString()
        );
    }

    private static LoanApplicationDocumentChecklistResponse toDocumentChecklistResponse(
            LoanApplicationDocumentChecklist checklistItem
    ) {
        return new LoanApplicationDocumentChecklistResponse(
                checklistItem.getId().toString(),
                checklistItem.getLoanApplication().getId().toString(),
                checklistItem.getDocumentType().name(),
                checklistItem.getDocumentType().getDisplayName(),
                checklistItem.isRequired(),
                checklistItem.getStatus().name(),
                checklistItem.getNote(),
                checklistItem.getFileName(),
                checklistItem.getFileReference(),
                checklistItem.getContentType(),
                checklistItem.getSourceReference(),
                checklistItem.getUploadedAt(),
                checklistItem.getUploadedByUsername(),
                checklistItem.getUpdatedByUsername(),
                checklistItem.getCreatedAt().toString(),
                checklistItem.getUpdatedAt().toString()
        );
    }

    public record LoanApplicationRequest(
            @NotNull UUID lspId,
            @NotNull UUID productId,
            @NotBlank String externalLoanId,
            @NotBlank String sourceChannel,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$", message = "PAN must be a valid 10-character PAN") String borrowerPan,
            @NotBlank String borrowerFullName,
            @NotBlank @Pattern(regexp = "^[0-9]{10,15}$", message = "Mobile must contain 10 to 15 digits") String borrowerMobile,
            @Email String borrowerEmail,
            @Past LocalDate borrowerDateOfBirth,
            @Size(max = 128) String borrowerCity,
            @Size(max = 128) String borrowerState,
            @Size(max = 64) String borrowerEmploymentType,
            @DecimalMin(value = "0.01") BigDecimal borrowerMonthlyIncome,
            @NotNull @DecimalMin("0.01") BigDecimal requestedAmount,
            @NotNull @Min(1) Integer tenureMonths
    ) {
    }

    public record LoanApplicationResponse(
            String id,
            String borrowerId,
            String borrowerFullName,
            String borrowerPan,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            String lspId,
            String lspCode,
            String lspName,
            String productId,
            String productCode,
            String productName,
            String externalLoanId,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            String status,
            String assignedToUsername,
            String assignedByUsername,
            Instant assignedAt,
            String createdAt
    ) {
    }

    public record LoanApplicationDetailResponse(
            String id,
            String borrowerId,
            String borrowerFullName,
            String borrowerPan,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            String lspId,
            String lspCode,
            String lspName,
            String productId,
            String productCode,
            String productName,
            String externalLoanId,
            String sourceChannel,
            BigDecimal requestedAmount,
            Integer tenureMonths,
            String status,
            String assignedToUsername,
            String assignedByUsername,
            Instant assignedAt,
            String createdAt,
            String updatedAt
    ) {
    }

    public record LoanApplicationIntakeAuditResponse(
            String id,
            String loanApplicationId,
            String actorUsername,
            String correlationId,
            String payloadJson,
            Instant createdAt
    ) {
    }

    public record LoanApplicationStatusTransitionRequest(
            @NotNull LoanApplicationStatus targetStatus,
            @Size(max = 500) String note
    ) {
    }

    public record LoanApplicationAssignmentRequest(
            String assigneeUsername,
            @Size(max = 500) String note
    ) {
    }

    public record LoanApplicationDocumentChecklistUpdateRequest(
            @NotNull LoanApplicationDocumentChecklistStatus status,
            @Size(max = 500) String note,
            @Size(max = 255) String fileName,
            @Size(max = 500) String fileReference,
            @Size(max = 500) String sourceReference,
            @Size(max = 128) String contentType
    ) {
    }

    public record LoanApplicationStatusTransitionResponse(
            String id,
            String loanApplicationId,
            String actorUsername,
            String fromStatus,
            String toStatus,
            String note,
            String correlationId,
            String createdAt
    ) {
    }

    public record LoanApplicationAssignmentEventResponse(
            String id,
            String loanApplicationId,
            String fromAssigneeUsername,
            String toAssigneeUsername,
            String actorUsername,
            String note,
            String correlationId,
            String createdAt
    ) {
    }

    public record LoanApplicationDocumentChecklistResponse(
            String id,
            String loanApplicationId,
            String documentType,
            String documentDisplayName,
            boolean required,
            String status,
            String note,
            String fileName,
            String fileReference,
            String contentType,
            String sourceReference,
            Instant uploadedAt,
            String uploadedByUsername,
            String updatedByUsername,
            String createdAt,
            String updatedAt
    ) {
    }
}
