package com.fintrack.api.exception;

import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns every exception that escapes a controller into the one {@link ApiError} shape.
 * <p>
 * The alternative - letting the servlet container render its default error page - produces
 * a different JSON structure for framework-level failures than for application ones, which
 * every client then has to special-case.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Everything the application raises on purpose. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError.Envelope> handleApi(ApiException ex, HttpServletRequest request) {
        return respond(ex.getCode(), ex.getMessage(), ex.getDetails(), request);
    }

    /** {@code @Valid} failures on a request body: report every bad field at once. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError.Envelope> handleBodyValidation(MethodArgumentNotValidException ex,
                                                                  HttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // First message wins: one complaint per field reads better than a list of three.
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields, request);
    }

    /** {@code @Validated} failures on path variables and query parameters. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError.Envelope> handleConstraint(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        Map<String, Object> fields = ex.getConstraintViolations().stream().collect(Collectors.toMap(
                violation -> {
                    String path = violation.getPropertyPath().toString();
                    return path.substring(path.lastIndexOf('.') + 1);
                },
                violation -> (Object) violation.getMessage(),
                (first, second) -> first,
                LinkedHashMap::new));

        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields, request);
    }

    /** Body that is not valid JSON, or that cannot be bound to the target type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError.Envelope> handleUnreadable(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        Throwable cause = ex.getCause();

        if (cause instanceof UnrecognizedPropertyException unrecognised) {
            return respond(ErrorCode.MALFORMED_REQUEST,
                    "Unknown field: " + unrecognised.getPropertyName(),
                    Map.of("field", unrecognised.getPropertyName(),
                            "known", unrecognised.getKnownPropertyIds()),
                    request);
        }
        if (cause instanceof InvalidFormatException invalid) {
            String field = invalid.getPath().isEmpty() ? "?"
                    : invalid.getPath().get(invalid.getPath().size() - 1).getPropertyName();
            return respond(ErrorCode.MALFORMED_REQUEST,
                    "Field has an invalid value: " + field,
                    Map.of("field", field, "expected", invalid.getTargetType().getSimpleName()),
                    request);
        }
        // The parser message names types and field paths, so it is logged rather than
        // returned - the client gets a generic reason, the server keeps the detail.
        log.warn("Unreadable request body on {}: {}", request.getRequestURI(),
                cause == null ? ex.getMessage() : cause.toString());
        return respond(ErrorCode.MALFORMED_REQUEST, "Request body is not readable JSON", Map.of(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError.Envelope> handleMissingParam(MissingServletRequestParameterException ex,
                                                                HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "Missing required parameter: " + ex.getParameterName(),
                Map.of("parameter", ex.getParameterName(), "expected", ex.getParameterType()),
                request);
    }

    /** For example, a malformed UUID in the path. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError.Envelope> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "Parameter has an invalid value: " + ex.getName(),
                Map.of("parameter", ex.getName()),
                request);
    }

    /**
     * The right path, the wrong verb.
     * <p>
     * Without this the exception reaches the catch-all and the caller is told the server
     * failed - a 500 for what is entirely a client mistake, and one that sends people looking
     * for an outage that is not there.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError.Envelope> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        Map<String, Object> details = ex.getSupportedHttpMethods() == null ? Map.of()
                : Map.of("supported", ex.getSupportedHttpMethods().stream().map(Object::toString).toList());

        return respond(ErrorCode.METHOD_NOT_ALLOWED,
                "%s is not supported on this endpoint".formatted(ex.getMethod()), details, request);
    }

    /** A body the endpoint cannot consume - typically a missing or wrong Content-Type. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError.Envelope> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "This endpoint does not accept that content type",
                Map.of("supported", ex.getSupportedMediaTypes().stream().map(Object::toString).toList()),
                request);
    }

    /**
     * An upload past the container's multipart limit.
     * <p>
     * Thrown before the request ever reaches a controller, so the receipt service's own size
     * check never sees it. 413 with a clear message beats the bare 500 the container would
     * otherwise produce.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError.Envelope> handleUploadTooLarge(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {

        return respond(ErrorCode.PAYLOAD_TOO_LARGE,
                "That file is too large. Receipts must be 5 MB or smaller.",
                Map.of(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError.Envelope> handleAuthentication(AuthenticationException ex,
                                                                  HttpServletRequest request) {
        // Deliberately vague: distinguishing "no such user" from "wrong password" tells an
        // attacker which addresses are registered.
        return respond(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password", Map.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError.Envelope> handleAccessDenied(AccessDeniedException ex,
                                                                HttpServletRequest request) {
        return respond(ErrorCode.FORBIDDEN, "You do not have access to this resource", Map.of(), request);
    }

    /** A unique or foreign-key constraint the service layer did not anticipate. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError.Envelope> handleIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Database constraint violated on {}", request.getRequestURI(), ex);
        return respond(ErrorCode.DUPLICATE_RESOURCE,
                "That change conflicts with existing data", Map.of(), request);
    }

    /** An unmapped URL. Without this, Spring renders its own error body instead. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError.Envelope> handleNoResource(NoResourceFoundException ex,
                                                              HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, "No endpoint for this path", Map.of(), request);
    }

    /**
     * Last resort. The real exception is logged with a stack trace; the client is told
     * nothing about it, because an internal message can leak table names and file paths.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError.Envelope> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Something went wrong on our side", Map.of(), request);
    }

    private ResponseEntity<ApiError.Envelope> respond(ErrorCode code, String message,
                                                      Map<String, Object> details,
                                                      HttpServletRequest request) {
        HttpStatus status = code.status();
        ApiError body = ApiError.of(code, message, details, request.getRequestURI());
        return ResponseEntity.status(status).body(body.wrap());
    }
}
