package com.example.hotel.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e, HttpServletRequest request) {
    return error(e.getStatus(), e.getCode(), e.getMessage(), request, Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    Map<String, String> violations = new LinkedHashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(field -> violations.putIfAbsent(field.getField(), field.getDefaultMessage()));
    return error(400, "VALIDATION_ERROR", "Request validation failed", request, violations);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    ConstraintViolationException.class,
    MissingServletRequestParameterException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest request) {
    return error(400, "BAD_REQUEST", "Malformed or invalid request", request, Map.of());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> forbidden(Exception e, HttpServletRequest request) {
    return error(403, "FORBIDDEN", "Access is denied", request, Map.of());
  }

  @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
  ResponseEntity<ApiError> conflict(Exception e, HttpServletRequest request) {
    return error(
        409,
        "CONFLICT",
        "Operation conflicts with existing data; retry if concurrent",
        request,
        Map.of());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> notFound(Exception e, HttpServletRequest request) {
    return error(404, "NOT_FOUND", "Resource not found", request, Map.of());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> method(Exception e, HttpServletRequest request) {
    return error(405, "METHOD_NOT_ALLOWED", "HTTP method is not supported", request, Map.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
    LoggerFactory.getLogger(getClass())
        .error("event=unhandled_error traceId={}", TraceFilter.traceId(request), e);
    return error(500, "INTERNAL_ERROR", "An unexpected error occurred", request, Map.of());
  }

  private ResponseEntity<ApiError> error(
      int status,
      String code,
      String message,
      HttpServletRequest request,
      Map<String, String> violations) {
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                Instant.now(),
                status,
                code,
                message,
                request.getRequestURI(),
                TraceFilter.traceId(request),
                violations));
  }
}
