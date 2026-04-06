package com.bhawana.lms.common.web;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, fieldErrors);
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
                    documentType.getDisplayName() + " must be VERIFIED before approval."
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials", request, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnhandled(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
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
        List<ApiError.FieldViolation> violations = fieldErrors.entrySet().stream()
                .map(entry -> new ApiError.FieldViolation(entry.getKey(), entry.getValue()))
                .toList();

        ApiError error = new ApiError(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                CorrelationIdHolder.get(),
                violations
        );
        return ResponseEntity.status(status).body(error);
    }
}
