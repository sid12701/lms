package com.bhawana.lms.web;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessDeniedExceptionHandler {

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiError> handleAccessDenied(Exception exception, HttpServletRequest request) {
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                "ACCESS_DENIED",
                "Access denied",
                request.getRequestURI(),
                CorrelationIdHolder.get(),
                java.util.List.of()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }
}
