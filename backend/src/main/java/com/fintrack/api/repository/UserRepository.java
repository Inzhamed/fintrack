package com.fintrack.api.repository;

import com.fintrack.api.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Matches the {@code lower(email)} unique index, so lookup and uniqueness agree.
     * Callers pass the address already normalised.
     */
    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT count(u) > 0 FROM User u WHERE lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
}
