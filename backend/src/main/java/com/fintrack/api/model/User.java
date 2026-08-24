package com.fintrack.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Stored as the user typed it; uniqueness is enforced case-insensitively by a
     * functional index on {@code lower(email)}. Always normalise before lookup.
     */
    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Column(name = "base_currency", nullable = false, length = 3)
    @Builder.Default
    private String baseCurrency = "DZD";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    /**
     * Constant hash code. An entity's identity is its id, but the id is null until the
     * row is inserted; a field-derived hash would change on persist and lose the object
     * inside any HashSet it had already been added to.
     */
    @Override
    public int hashCode() {
        return User.class.hashCode();
    }
}
