package com.fintrack.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * No @Email or @Size here on purpose. Validating the shape of a login credential leaks
 * information and rejects legacy accounts; a wrong value should fail authentication, not
 * validation, so that every bad login looks identical from outside.
 */
public record LoginRequest(
        @NotBlank(message = "Email is required") String email,
        @NotBlank(message = "Password is required") String password
) {}
