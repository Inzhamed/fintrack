package com.fintrack.api.service;

import com.fintrack.api.config.JwtProperties;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.exception.ErrorCode;
import com.fintrack.api.model.RefreshToken;
import com.fintrack.api.model.User;
import com.fintrack.api.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues, redeems and revokes refresh tokens.
 *
 * <h2>Why these are not JWTs</h2>
 * A refresh token's whole purpose is to be revocable, which a self-contained signed token
 * cannot be without a server-side lookup anyway. So this is a plain 256-bit random string
 * checked against a table - simpler, and it carries no readable claims if it leaks.
 *
 * <h2>Only the hash is stored</h2>
 * The raw token is returned to the caller once and never persisted. An attacker who reads
 * the database therefore gets SHA-256 digests, which cannot be presented to the refresh
 * endpoint. Plain SHA-256 rather than BCrypt is deliberate and safe here: unlike a password,
 * the input is 256 bits of uniform randomness, so there is no search space to slow down -
 * and the lookup has to be an indexed exact match, which a salted hash cannot provide.
 *
 * <h2>Rotation and reuse detection</h2>
 * Redeeming a token revokes it and issues a replacement. If a token that was already
 * redeemed is presented again, the pair has been cloned - the legitimate holder and an
 * attacker both have a copy - so every token for that user is revoked at once, forcing a
 * fresh login. This is the standard OAuth 2 BCP response to refresh-token replay.
 */
@Service
@Slf4j
public class RefreshTokenService {

    /** 256 bits of entropy, URL-safe base64 encoded. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    /**
     * Runs the breach response in its own transaction.
     * <p>
     * Reuse detection has to both write (revoke the family) and fail (reject the request).
     * Doing them in one transaction is self-defeating: throwing rolls the revocation back,
     * so the attacker's token survives the very check meant to kill it. Committing the
     * revocation independently is what makes it stick.
     */
    private final TransactionTemplate revocationTransaction;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               JwtProperties jwtProperties,
                               PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.revocationTransaction = new TransactionTemplate(transactionManager);
        this.revocationTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Creates a new refresh token for the user.
     *
     * @return the raw token - the caller must return it to the client, because it cannot be
     *         recovered from the database afterwards
     */
    @Transactional
    public String issue(User user, String userAgent, String ipAddress) {
        String raw = generateToken();

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(raw))
                .expiresAt(Instant.now().plus(jwtProperties.refreshTokenTtl()))
                .userAgent(truncate(userAgent, 255))
                .ipAddress(truncate(ipAddress, 45))
                .build();

        refreshTokenRepository.save(token);
        return raw;
    }

    /**
     * Validates a refresh token and consumes it.
     *
     * @return the owning user, for whom the caller should now issue a fresh token pair
     * @throws ApiException if the token is unknown, expired, or already used
     */
    @Transactional
    public User redeem(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID,
                        "Refresh token is not valid"));

        if (token.isRevoked()) {
            // Presenting an already-redeemed token means a copy is in circulation. Revoke
            // the whole family rather than trying to work out which holder is genuine.
            User owner = token.getUser();
            log.warn("Refresh token reuse detected for user {} - revoking all sessions",
                    owner.getId());

            // Committed separately, so the throw below cannot undo it.
            revocationTransaction.executeWithoutResult(status ->
                    refreshTokenRepository.revokeAllForUser(owner, Instant.now()));

            throw new ApiException(ErrorCode.TOKEN_INVALID,
                    "Refresh token has already been used. Please sign in again.");
        }

        if (token.isExpired()) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED,
                    "Refresh token has expired. Please sign in again.");
        }

        token.revoke();   // Rotation: this token is spent the moment it is accepted.
        return token.getUser();
    }

    /** Signs out one session. Revoking an unknown token is a no-op, not an error. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(RefreshToken::revoke);
    }

    /** Signs out every session for the user. */
    @Transactional
    public int revokeAll(User user) {
        return refreshTokenRepository.revokeAllForUser(user, Instant.now());
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** SHA-256, lowercase hex - fixed 64 characters, matching the column definition. */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JVM, so this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", ex);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
