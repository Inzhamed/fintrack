package com.fintrack.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param password minimum length is the primary defence; a long passphrase beats a short
 *                 string with a symbol bolted on, so no character-class rule is imposed.
 *                 The maximum exists because BCrypt silently ignores bytes past 72.
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code, e.g. DZD")
        String baseCurrency
) {
    /** Falls back to the Algerian dinar when the client does not specify one. */
    public String baseCurrencyOrDefault() {
        return baseCurrency == null || baseCurrency.isBlank() ? "DZD" : baseCurrency;
    }
}
