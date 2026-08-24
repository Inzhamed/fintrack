package com.fintrack.api.security;

import com.fintrack.api.config.JwtProperties;
import com.fintrack.api.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies stateless access tokens (HMAC-SHA).
 * <p>
 * Access tokens are deliberately <em>not</em> revocable: checking a denylist on every request
 * would put a datastore read back into the hot path and give up the only real benefit of a
 * stateless token. They are instead kept short-lived, and revocation is handled at the
 * refresh-token layer by {@link com.fintrack.api.service.RefreshTokenService}.
 * <p>
 * This class knows nothing about refresh tokens: those are opaque random strings, not JWTs,
 * so that possession of one reveals nothing and forging one is impossible without the table.
 */
@Service
public class JwtService {

    /** Custom claims. Kept short - every byte ships on every request. */
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = buildKey(properties.secret());
    }

    /**
     * Builds the signing key. jjwt picks the HMAC variant from the key length - 256 bits
     * gives HS256, 384 gives HS384, 512 gives HS512 - and refuses anything shorter than
     * 256. Its own exception does not say how to fix that, so it is rethrown with
     * instructions.
     */
    private static SecretKey buildKey(String base64Secret) {
        byte[] material;
        try {
            material = Decoders.BASE64.decode(base64Secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "fintrack.security.jwt.secret must be base64-encoded. "
                            + "Generate one with: openssl rand -base64 48", ex);
        }
        try {
            return Keys.hmacShaKeyFor(material);
        } catch (WeakKeyException ex) {
            throw new IllegalStateException(
                    "fintrack.security.jwt.secret is too short: HMAC-SHA needs at least 256 bits "
                            + "(32 bytes) of key material. Generate one with: openssl rand -base64 48", ex);
        }
    }

    /** Mints an access token carrying the user id as subject, plus email and role. */
    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenTtl());

        return Jwts.builder()
                .subject(user.getId().toString())
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature, issuer and expiry, and returns the claims.
     *
     * @return empty when the token is absent, malformed, expired, foreign, or not an access token
     */
    public Optional<Claims> parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Reject any token that was minted for a different purpose, so a token issued
            // elsewhere in the system can never be replayed as an access token.
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers expired, tampered, unsupported and malformed tokens alike. The filter
            // treats them all the same way: the request continues unauthenticated.
            return Optional.empty();
        }
    }

    /** Extracts the user id from verified claims. */
    public Optional<UUID> userId(Claims claims) {
        try {
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Seconds until expiry, for the {@code expiresIn} field of a token response. */
    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
