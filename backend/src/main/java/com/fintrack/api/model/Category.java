package com.fintrack.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A spending or earning bucket.
 * <p>
 * A null {@link #owner} marks a global default category seeded by migration V2 and visible
 * to every account. User-created categories are private to their owner.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null for the global defaults shared by all users. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User owner;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryType type;

    /** Hex colour such as {@code #22c55e}, rendered directly by the frontend. */
    @Column(length = 7)
    private String color;

    @Column(length = 40)
    private String icon;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isGlobal() {
        return owner == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Category other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Category.class.hashCode();
    }
}
