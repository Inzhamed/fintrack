package com.fintrack.api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The single response body every failed request produces, wrapped as
 * {@code {"error": {...}}} so a client can tell an error from a payload by shape alone.
 *
 * @param code    stable, machine-readable identifier — branch on this
 * @param message human-readable explanation, safe to show a user
 * @param details field-level errors or contextual data; omitted when empty
 * @param path    the request URI that failed
 */
public record ApiError(
        ErrorCode code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details,
        String path,
        Instant timestamp
) {
    public static ApiError of(ErrorCode code, String message, Map<String, Object> details, String path) {
        return new ApiError(code, message, details == null ? Map.of() : details, path, Instant.now());
    }

    /** Envelope so the wire format is {@code {"error": { ... }}}. */
    public record Envelope(ApiError error) {}

    public Envelope wrap() {
        return new Envelope(this);
    }
}
