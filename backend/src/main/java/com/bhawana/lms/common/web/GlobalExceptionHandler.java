package com.bhawana.lms.common.web;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.CursorExpiredException;
import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.DocumentStorageMisconfiguredException;
import com.bhawana.lms.common.api.error.DocumentStorageUnavailableException;
import com.bhawana.lms.common.api.error.DocumentUploadRequiredException;
import com.bhawana.lms.common.api.error.KycCompletionRequiredException;
import com.bhawana.lms.common.api.error.LspStatusUpdateException;
import com.bhawana.lms.common.api.error.LspSurfaceIpAccessDeniedException;
import com.bhawana.lms.common.api.error.PayloadTooLargeException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.common.api.error.UnsupportedDocumentPreviewException;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.security.LspInactiveAuthenticationException;
import com.bhawana.lms.tenant.MissingTenantContextException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String DOCUMENT_STORAGE_UNAVAILABLE_COUNTER = "lms.document.storage.unavailable";
    private static final String TENANT_SCOPE_MISSING_COUNTER = "lms.tenant.scope.missing";
    private static final String UNTYPED_API_ERROR_COUNTER = "lms.api.untyped_error";
    private static final String ACCESS_DENIED_ERROR_CODE = "ACCESS_DENIED";
    private static final String BORROWER_PAN_CONFLICT_ERROR_CODE = "BORROWER_PAN_CONFLICT";
    private static final String CONFLICT_ERROR_CODE = "CONFLICT";
    private static final String DUPLICATE_EXTERNAL_LOAN_ID_ERROR_CODE = "DUPLICATE_EXTERNAL_LOAN_ID";
    private static final String IDEMPOTENCY_CONFLICT_ERROR_CODE = "IDEMPOTENCY_CONFLICT";
    private static final String INTERNAL_SERVER_ERROR_CODE = "INTERNAL_SERVER_ERROR";
    private static final String INVALID_REQUEST_ERROR_CODE = "INVALID_REQUEST";
    private static final String NOT_FOUND_ERROR_CODE = "NOT_FOUND";
    private static final String PAYLOAD_TOO_LARGE_ERROR_CODE = "PAYLOAD_TOO_LARGE";
    private static final String VALIDATION_FAILED_ERROR_CODE = "VALIDATION_FAILED";

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), friendlyValidationMessage(fieldError.getDefaultMessage()));
        }

        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_ERROR_CODE, "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            int dot = path.lastIndexOf('.');
            String field = dot >= 0 ? path.substring(dot + 1) : path;
            fieldErrors.put(field, friendlyValidationMessage(violation.getMessage()));
        }

        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_ERROR_CODE, "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleHandlerMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String field = result.getMethodParameter().getParameterName();
            if (field == null) {
                field = "parameter";
            }
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                fieldErrors.put(field, friendlyValidationMessage(error.getDefaultMessage()));
            }
        }

        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_ERROR_CODE, "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(KycCompletionRequiredException.class)
    public ResponseEntity<ApiError> handleKycCompletionRequired(
            KycCompletionRequiredException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (LoanApplicationDocumentType documentType : exception.getBlockingDocumentTypes()) {
            fieldErrors.put(
                    documentType.name(),
                    documentType.getDisplayName() + " must be uploaded before approval."
            );
        }

        return build(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "KYC_COMPLETION_REQUIRED",
                exception.getMessage(),
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(DocumentUploadRequiredException.class)
    public ResponseEntity<ApiError> handleDocumentUploadRequired(
            DocumentUploadRequiredException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (LoanApplicationDocumentType documentType : exception.getBlockingDocumentTypes()) {
            fieldErrors.put(
                    documentType.name(),
                    documentType.getDisplayName() + " must be uploaded before disbursement."
            );
        }

        return build(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DOCUMENT_UPLOAD_REQUIRED",
                exception.getMessage(),
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ApiError> handleBusinessRuleViolation(
            BusinessRuleViolationException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.getErrorCode(),
                exception.getMessage(),
                request,
                exception.getFieldErrors()
        );
    }

    @ExceptionHandler(CursorExpiredException.class)
    public ResponseEntity<ApiError> handleCursorExpired(
            CursorExpiredException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.GONE,
                exception.getErrorCode(),
                exception.getMessage(),
                request,
                exception.getFieldErrors()
        );
    }

    @ExceptionHandler(ApiConflictException.class)
    public ResponseEntity<ApiError> handleConflict(
            ApiConflictException exception,
            HttpServletRequest request
    ) {
        ResponseEntity<ApiError> response = build(
                HttpStatus.CONFLICT,
                exception.getErrorCode(),
                exception.getMessage(),
                request,
                Map.of()
        );
        exception.getRetryAfterSeconds().ifPresent(seconds ->
                response.getHeaders().set("Retry-After", Long.toString(seconds))
        );
        return response;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        ConstraintViolationResolution resolution = resolveConstraintViolation(exception);
        if (resolution.status() == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error(
                    "Data integrity violation on {} {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    exception
            );
        } else {
            log.warn(
                    "Data integrity violation on {} {} (constraint={}, sqlState={})",
                    request.getMethod(),
                    request.getRequestURI(),
                    resolution.constraintName(),
                    resolution.sqlState()
            );
        }
        return build(
                resolution.status(),
                resolution.errorCode(),
                resolution.message(),
                request,
                resolution.fieldErrors()
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND_ERROR_CODE, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> handleDocumentNotFound(
            DocumentNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleEntityNotFound(
            EntityNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND_ERROR_CODE, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(UnsupportedDocumentPreviewException.class)
    public ResponseEntity<ApiError> handleUnsupportedDocumentPreview(
            UnsupportedDocumentPreviewException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "DOCUMENT_PREVIEW_UNSUPPORTED",
                exception.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(DocumentStorageUnavailableException.class)
    public ResponseEntity<ApiError> handleDocumentStorageUnavailable(
            DocumentStorageUnavailableException exception,
            HttpServletRequest request
    ) {
        String provider = exception.getProviderName() == null || exception.getProviderName().isBlank()
                ? "unknown"
                : exception.getProviderName();
        meterRegistry.counter(DOCUMENT_STORAGE_UNAVAILABLE_COUNTER, "provider", provider).increment();
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DOCUMENT_STORAGE_UNAVAILABLE",
                "Document storage is temporarily unavailable. Please retry.",
                request,
                Map.of("retryable", "true", "provider", provider)
        );
    }

    @ExceptionHandler(DocumentStorageMisconfiguredException.class)
    public ResponseEntity<ApiError> handleDocumentStorageMisconfigured(
            DocumentStorageMisconfiguredException exception,
            HttpServletRequest request
    ) {
        log.error(
                "Document storage misconfigured for provider {} (missing: {})",
                exception.getProviderName(),
                exception.getMissingField()
        );
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "DOCUMENT_STORAGE_MISCONFIGURED",
                exception.getMessage(),
                request,
                Map.of(
                        "provider", exception.getProviderName(),
                        "missingField", exception.getMissingField()
                )
        );
    }

    @ExceptionHandler(LspStatusUpdateException.class)
    public ResponseEntity<ApiError> handleLspStatusUpdate(
            LspStatusUpdateException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_REQUEST, exception.getErrorCode(), exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingRequestHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        String headerName = exception.getHeaderName();
        String message = headerName + " header is required.";
        return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST_ERROR_CODE, message, request, Map.of(headerName, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        meterRegistry.counter(UNTYPED_API_ERROR_COUNTER).increment();
        String friendly = friendlyIllegalArgumentMessage(exception.getMessage());
        log.warn(
                "Untyped IllegalArgumentException mapped to 400 INVALID_REQUEST at {}: {}",
                request.getRequestURI(),
                exception.getMessage()
        );
        return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST_ERROR_CODE, friendly, request, Map.of());
    }

    @ExceptionHandler(LspSurfaceIpAccessDeniedException.class)
    public ResponseEntity<ApiError> handleLspSurfaceIpAccessDenied(
            LspSurfaceIpAccessDeniedException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.FORBIDDEN, exception.getErrorCode(), exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials", request, Map.of());
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "User account is disabled", request, Map.of());
    }

    @ExceptionHandler(LspInactiveAuthenticationException.class)
    public ResponseEntity<ApiError> handleLspInactive(
            LspInactiveAuthenticationException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.UNAUTHORIZED, "LSP_INACTIVE", "LSP is not active", request, Map.of());
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiError> handleLocked(LockedException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED", "User account is locked", request, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ACCESS_DENIED_ERROR_CODE, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiError> handleAuthorizationDenied(
            AuthorizationDeniedException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.FORBIDDEN, ACCESS_DENIED_ERROR_CODE, "Access denied", request, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException exception, HttpServletRequest request) {
        return build(
                HttpStatus.NOT_FOUND,
                NOT_FOUND_ERROR_CODE,
                "Resource not found: " + request.getRequestURI(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        String parameterName = exception.getName();
        Object rejectedValue = exception.getValue();
        Class<?> targetType = exception.getRequiredType();
        String message;
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (targetType != null && targetType.isEnum()) {
            Object[] allowed = targetType.getEnumConstants();
            StringBuilder allowedList = new StringBuilder();
            for (int i = 0; i < allowed.length; i++) {
                if (i > 0) allowedList.append(", ");
                allowedList.append(((Enum<?>) allowed[i]).name());
            }
            message = "Invalid value '" + rejectedValue + "' for parameter '" + parameterName
                    + "'. Allowed values: " + allowedList + ".";
            fieldErrors.put(parameterName, message);
        } else {
            message = "Invalid value '" + rejectedValue + "' for parameter '" + parameterName + "'.";
            fieldErrors.put(parameterName, message);
        }
        return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST_ERROR_CODE, message, request, fieldErrors);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiError> handlePayloadTooLarge(
            PayloadTooLargeException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.PAYLOAD_TOO_LARGE,
                PAYLOAD_TOO_LARGE_ERROR_CODE,
                "Request body exceeds the maximum permitted size.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        if (causedBy(exception, PayloadTooLargeException.class)) {
            return build(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    PAYLOAD_TOO_LARGE_ERROR_CODE,
                    "Request body exceeds the maximum permitted size.",
                    request,
                    Map.of()
            );
        }
        Throwable cause = exception.getMostSpecificCause();
        String message;
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (cause instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException unrecognizedProperty) {
            String field = unrecognizedProperty.getPropertyName();
            message = "Unknown field '" + field + "' is not permitted.";
            fieldErrors.put(field, message);
        } else if (cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException invalidFormat) {
            Class<?> targetType = invalidFormat.getTargetType();
            String field = invalidFormat.getPath().isEmpty()
                    ? "body"
                    : invalidFormat.getPath().get(invalidFormat.getPath().size() - 1).getFieldName();
            if (targetType != null && targetType.isEnum()) {
                Object[] allowed = targetType.getEnumConstants();
                StringBuilder allowedList = new StringBuilder();
                for (int i = 0; i < allowed.length; i++) {
                    if (i > 0) allowedList.append(", ");
                    allowedList.append(((Enum<?>) allowed[i]).name());
                }
                message = "Invalid value '" + invalidFormat.getValue() + "' for field '" + field
                        + "'. Allowed values: " + allowedList + ".";
            } else {
                message = "Invalid value '" + invalidFormat.getValue() + "' for field '" + field + "'.";
            }
            fieldErrors.put(field, message);
        } else {
            message = "Request body is malformed or could not be parsed.";
        }
        return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST_ERROR_CODE, message, request, fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(
            MissingServletRequestPartException exception,
            HttpServletRequest request
    ) {
        String message = "Required multipart part '" + exception.getRequestPartName() + "' is missing.";
        return build(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST_ERROR_CODE,
                message,
                request,
                Map.of(exception.getRequestPartName(), message)
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.PAYLOAD_TOO_LARGE,
                PAYLOAD_TOO_LARGE_ERROR_CODE,
                "Uploaded content exceeds the maximum permitted size.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION",
                "The resource was modified by another request. Retry the operation.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(MissingTenantContextException.class)
    public ResponseEntity<ApiError> handleMissingTenantScope(
            MissingTenantContextException exception,
            HttpServletRequest request
    ) {
        meterRegistry.counter(TENANT_SCOPE_MISSING_COUNTER).increment();
        log.error(
                "Tenant data-access scope missing on {} {} — request reached data access without a resolved scope",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "TENANT_SCOPE_MISSING",
                "An unexpected error occurred",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnhandled(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_SERVER_ERROR_CODE,
                "An unexpected error occurred",
                request,
                Map.of()
        );
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        return ResponseEntity.status(status).body(ApiError.of(
                status.value(),
                code,
                message,
                request.getRequestURI(),
                CorrelationIdHolder.get(),
                fieldErrors
        ));
    }

    private static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static String friendlyValidationMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "This field is invalid.";
        }
        if (raw.contains("must not be blank")
                || raw.contains("must not be null")
                || raw.contains("must not be empty")) {
            return "This field is required.";
        }
        if (raw.contains("must be a well-formed email address")) {
            return "Enter a valid email address.";
        }
        if (raw.contains("must match")) {
            return "Enter a value in the required format.";
        }
        if (raw.contains("must be a past date") || raw.contains("must be in the past")) {
            return "Enter a date in the past.";
        }
        if (raw.contains("Idempotency-Key must be a UUID v4")) {
            return "Idempotency-Key must be a valid UUID v4.";
        }
        if (raw.startsWith("must be greater than or equal to ")) {
            return "Enter a value greater than or equal to "
                    + raw.substring("must be greater than or equal to ".length())
                    + ".";
        }
        if (raw.startsWith("must be less than or equal to ")) {
            return "Enter a value less than or equal to "
                    + raw.substring("must be less than or equal to ".length())
                    + ".";
        }
        if (raw.startsWith("size must be between ")) {
            return "Enter text with "
                    + raw.substring("size must be between ".length())
                    + " characters.";
        }
        return raw;
    }

    private static String friendlyIllegalArgumentMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "The request could not be processed.";
        }
        if (raw.contains("Idempotency-Key must be a UUID v4")) {
            return "Idempotency-Key must be a valid UUID v4.";
        }
        if (raw.startsWith("Unknown LSP id:")) {
            return "The selected LSP was not found.";
        }
        return raw;
    }

    private static ConstraintViolationResolution resolveConstraintViolation(DataIntegrityViolationException exception) {
        String constraintName = null;
        String sqlState = null;
        org.hibernate.exception.ConstraintViolationException constraintViolation =
                findConstraintViolation(exception);
        if (constraintViolation != null) {
            constraintName = normalizeConstraintName(constraintViolation.getConstraintName());
            if (constraintViolation.getSQLException() != null) {
                sqlState = constraintViolation.getSQLException().getSQLState();
            }
        }
        if (sqlState == null) {
            Throwable cause = exception.getMostSpecificCause();
            if (cause instanceof SQLException sqlException) {
                sqlState = sqlException.getSQLState();
            }
        }

        if ("23505".equals(sqlState)) {
            String errorCode = mapUniqueConstraintErrorCode(constraintName);
            String message = switch (errorCode) {
                case BORROWER_PAN_CONFLICT_ERROR_CODE -> "A borrower with this PAN already exists.";
                case DUPLICATE_EXTERNAL_LOAN_ID_ERROR_CODE -> "An application with this external loan id already exists for the LSP.";
                case IDEMPOTENCY_CONFLICT_ERROR_CODE -> "Idempotency-Key has already been used.";
                case CONFLICT_ERROR_CODE -> "The request conflicts with existing data.";
                default -> "The request conflicts with existing data.";
            };
            return new ConstraintViolationResolution(
                    HttpStatus.CONFLICT,
                    errorCode,
                    message,
                    constraintName,
                    sqlState,
                    Map.of()
            );
        }

        if ("23514".equals(sqlState) || "22001".equals(sqlState)) {
            return new ConstraintViolationResolution(
                    HttpStatus.BAD_REQUEST,
                    VALIDATION_FAILED_ERROR_CODE,
                    "Request validation failed",
                    constraintName,
                    sqlState,
                    Map.of()
            );
        }

        return new ConstraintViolationResolution(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_SERVER_ERROR_CODE,
                "An unexpected error occurred",
                constraintName,
                sqlState,
                Map.of()
        );
    }

    private static org.hibernate.exception.ConstraintViolationException findConstraintViolation(
            DataIntegrityViolationException exception
    ) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException constraintViolation) {
                return constraintViolation;
            }
        }
        return null;
    }

    private static String mapUniqueConstraintErrorCode(String constraintName) {
        if (constraintName == null) {
            return CONFLICT_ERROR_CODE;
        }
        return switch (constraintName) {
            case "uk_loan_payment_transaction_idempotency_key" -> IDEMPOTENCY_CONFLICT_ERROR_CODE;
            case "uk_borrower_pan" -> BORROWER_PAN_CONFLICT_ERROR_CODE;
            case "uk_loan_application_lsp_external" -> DUPLICATE_EXTERNAL_LOAN_ID_ERROR_CODE;
            default -> constraintName.contains("idempotency") ? IDEMPOTENCY_CONFLICT_ERROR_CODE : CONFLICT_ERROR_CODE;
        };
    }

    private static String normalizeConstraintName(String constraintName) {
        if (constraintName == null) {
            return null;
        }
        return constraintName.toLowerCase().replace("\"", "");
    }

    private record ConstraintViolationResolution(
            HttpStatus status,
            String errorCode,
            String message,
            String constraintName,
            String sqlState,
            Map<String, String> fieldErrors
    ) {
    }
}
