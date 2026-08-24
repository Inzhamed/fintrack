package com.fintrack.api.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code fintrack.security.jwt.*}.
 * <p>
 * {@code secret} has no default anywhere in the configuration, so a deployment that forgets
 * to supply {@code JWT_SECRET} fails at startup instead of silently signing tokens with a
 * value that is public knowledge.
 *
 * @param secret          base64-encoded HMAC key, at least 256 bits; the algorithm (HS256/
 *                        384/512) follows from its length (generate with
 *                        {@code openssl rand -base64 48})
 * @param issuer          the {@code iss} claim, verified on every parse
 * @param accessTokenTtl  how long an access token stays valid - short, since it cannot be revoked
 * @param refreshTokenTtl how long a refresh token stays valid - long, but revocable and rotated
 */
@Validated
@ConfigurationProperties(prefix = "fintrack.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
    public JwtProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
    }
}
