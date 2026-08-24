package com.fintrack.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A single issued refresh token.
 * <p>
 * Only the SHA-256 hash of the token is persisted. The raw value is handed to the client
 * exactly once, at issue time, and is unrecoverable from the database — so a dump of this
 * table cannot be replayed to mint access tokens.
 * <p>
 * Tokens are rotated: redeeming one revokes it and issues a replacement.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    /** A token is usable only while it is neither revoked nor past its expiry. */
    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }
}
