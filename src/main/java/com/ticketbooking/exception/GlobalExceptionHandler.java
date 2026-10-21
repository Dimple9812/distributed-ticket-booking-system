package com.ticketbooking.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        if (ex.getCode().status().is5xxServerError()) {
            log.warn("Request failed: code={} message={}", ex.getCode(), ex.getMessage());
        } else {
            log.debug("Business rejection: code={} message={}", ex.getCode(), ex.getMessage());
        }
        return respond(ApiErrorFactory.build(ex.getCode(), ex.getMessage(), request.getRequestURI(), ex.getDetails(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        List<ApiError.FieldViolation> globals = ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> new ApiError.FieldViolation(ge.getObjectName(), ge.getDefaultMessage()))
                .toList();
        List<ApiError.FieldViolation> all = new java.util.ArrayList<>(violations);
        all.addAll(globals);
        return respond(ApiErrorFactory.build(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                request.getRequestURI(), Map.of(), all));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new ApiError.FieldViolation(r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        return respond(ApiErrorFactory.build(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                request.getRequestURI(), Map.of(), violations));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return respond(ApiErrorFactory.build(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                request.getRequestURI(), Map.of(), violations));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        String message = ex instanceof MethodArgumentTypeMismatchException mm
                ? "Parameter '" + mm.getName() + "' has an invalid value"
                : ex instanceof MissingServletRequestParameterException mp
                ? "Missing required parameter '" + mp.getParameterName() + "'"
                : "Request body is missing or malformed";
        return respond(ApiErrorFactory.build(ErrorCode.MALFORMED_REQUEST, message, request.getRequestURI()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        ErrorCode code = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? ErrorCode.IDEMPOTENCY_KEY_MISSING : ErrorCode.MALFORMED_REQUEST;
        return respond(ApiErrorFactory.build(code, "Missing required header '" + ex.getHeaderName() + "'",
                request.getRequestURI()));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiError> handleAccessDenied(Exception ex, HttpServletRequest request) {
        return respond(ApiErrorFactory.build(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action",
                request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(ApiErrorFactory.build(ErrorCode.RESOURCE_NOT_FOUND, "No endpoint at this path", request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return respond(ApiErrorFactory.build(ErrorCode.MALFORMED_REQUEST, "HTTP method " + ex.getMethod() + " is not supported here",
                request.getRequestURI()));
    }

    /**
     * Database constraints are the last line of defence. A violation that reaches
     * this handler means a concurrent request won a race the application checks
     * did not catch; we translate it into a conflict rather than a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String sqlState = findSqlState(ex);
        log.info("Data integrity violation sqlState={} message={}", sqlState, ex.getMostSpecificCause().getMessage());
        ErrorCode code = switch (sqlState == null ? "" : sqlState) {
            case "23P01" -> ErrorCode.SCREENING_OVERLAP;
            case "23505" -> ErrorCode.DUPLICATE_RESOURCE;
            default -> ErrorCode.CONCURRENT_MODIFICATION;
        };
        return respond(ApiErrorFactory.build(code, "The request conflicts with the current state of the resource",
                request.getRequestURI()));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleLocking(Exception ex, HttpServletRequest request) {
        log.info("Concurrent modification detected: {}", ex.getMessage());
        return respond(ApiErrorFactory.build(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified concurrently; please retry", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(ApiErrorFactory.build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred",
                request.getRequestURI()));
    }

    private static ResponseEntity<ApiError> respond(ApiError body) {
        return ResponseEntity.status(body.status()).body(body);
    }

    private static String findSqlState(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
