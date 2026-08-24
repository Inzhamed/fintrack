package com.fintrack.api.exception;

import lombok.Getter;

import java.util.Map;

/**
 * Base type for every failure the application raises deliberately.
 * <p>
 * Carrying the {@link ErrorCode} on the exception means the HTTP status is decided at the
 * point the problem is detected, not guessed at by the handler.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    // --- Factories for the cases that come up constantly -------------------------------

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND,
                "%s not found".formatted(resource),
                Map.of("resource", resource, "id", String.valueOf(id)));
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException validation(String message, Map<String, Object> details) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, details);
    }
}
