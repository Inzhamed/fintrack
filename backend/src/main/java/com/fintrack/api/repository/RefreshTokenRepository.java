package com.fintrack.api.repository;

import com.fintrack.api.model.RefreshToken;
import com.fintrack.api.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Fetches the owning user eagerly: the refresh flow needs it immediately, and a lazy
     * proxy here would mean a second query on every token rotation.
     */
    @Query("SELECT t FROM RefreshToken t JOIN FETCH t.user WHERE t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHash(@Param("hash") String hash);

    /** Global sign-out: revokes every token the user still holds. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.user = :user AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("user") User user, @Param("now") Instant now);

    /** Housekeeping for expired or long-revoked rows. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
