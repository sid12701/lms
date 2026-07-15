package com.bhawana.lms.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerDataIntegrityTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(mock(io.micrometer.core.instrument.MeterRegistry.class));
        request = mock(HttpServletRequest.class);
        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/v1/test");
        org.mockito.Mockito.when(request.getMethod()).thenReturn("POST");
    }

    @Test
    void mapsBorrowerPanUniqueViolationTo409() {
        ResponseEntity<?> response = handler.handleDataIntegrityViolation(
                dataIntegrityViolation("uk_borrower_pan", "23505"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((com.bhawana.lms.common.api.ApiError) response.getBody()).error()).isEqualTo("BORROWER_PAN_CONFLICT");
    }

    @Test
    void mapsPaymentIdempotencyUniqueViolationTo409() {
        ResponseEntity<?> response = handler.handleDataIntegrityViolation(
                dataIntegrityViolation("uk_loan_payment_transaction_idempotency_key", "23505"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((com.bhawana.lms.common.api.ApiError) response.getBody()).error()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void mapsUnknownUniqueViolationTo409Conflict() {
        ResponseEntity<?> response = handler.handleDataIntegrityViolation(
                dataIntegrityViolation("uk_some_other_unique", "23505"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((com.bhawana.lms.common.api.ApiError) response.getBody()).error()).isEqualTo("CONFLICT");
    }

    @Test
    void mapsCheckViolationTo400ValidationFailed() {
        ResponseEntity<?> response = handler.handleDataIntegrityViolation(
                dataIntegrityViolation("chk_example", "23514"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((com.bhawana.lms.common.api.ApiError) response.getBody()).error()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void mapsForeignKeyViolationTo500() {
        ResponseEntity<?> response = handler.handleDataIntegrityViolation(
                dataIntegrityViolation("fk_example", "23503"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(((com.bhawana.lms.common.api.ApiError) response.getBody()).error()).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    private static DataIntegrityViolationException dataIntegrityViolation(String constraintName, String sqlState) {
        SQLException sqlException = new SQLException("constraint violation", sqlState);
        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "violation",
                sqlException,
                constraintName
        );
        return new DataIntegrityViolationException("violation", constraintViolation);
    }
}
