package com.bhawana.lms.common.web;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
            fieldErrors.put(field, violation.getMessage());
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

    @ExceptionHandler(ApiConflictException.class)
    public ResponseEntity<ApiError> handleConflict(
            ApiConflictException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.CONFLICT, exception.getErrorCode(), exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(LspStatusUpdateException.class)
    public ResponseEntity<ApiError> handleLspStatusUpdate(
            LspStatusUpdateException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_REQUEST, exception.getErrorCode(), exception.getMessage(), request, Map.of());
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

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiError> handleAuthorizationDenied(
            AuthorizationDeniedException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied", request, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException exception, HttpServletRequest request) {
        return build(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
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
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        Throwable cause = exception.getMostSpecificCause();
        String message;
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException invalidFormat) {
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
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request, fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(
            MissingServletRequestPartException exception,
            HttpServletRequest request
    ) {
        String message = "Required multipart part '" + exception.getRequestPartName() + "' is missing.";
        return build(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                message,
                request,
                Map.of(exception.getRequestPartName(), message)
        );
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
        return ResponseEntity.status(status).body(ApiError.of(
                status.value(),
                code,
                message,
                request.getRequestURI(),
                CorrelationIdHolder.get(),
                fieldErrors
        ));
    }
}
