package com.fintrack.api.dto.auth;

import com.fintrack.api.model.User;

import java.time.Instant;
import java.util.UUID;

/** The public view of an account. Note the absence of any password field. */
public record UserResponse(
        UUID id,
        String email,
        String name,
        String role,
        String baseCurrency,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getBaseCurrency(),
                user.getCreatedAt());
    }
}
