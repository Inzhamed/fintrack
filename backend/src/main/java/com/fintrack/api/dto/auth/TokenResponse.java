package com.fintrack.api.dto.auth;

/**
 * Issued by register, login and refresh alike.
 *
 * @param accessToken  short-lived JWT for the Authorization header
 * @param refreshToken opaque random string; the only copy the client will ever receive,
 *                     since the server stores nothing but its hash
 * @param expiresIn    seconds until accessToken expires, so the client can refresh ahead of time
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user
) {
    public static TokenResponse of(String accessToken, String refreshToken,
                                   long expiresIn, UserResponse user) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
