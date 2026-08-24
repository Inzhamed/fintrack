package com.fintrack.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One movement of money.
 * <p>
 * {@link #amount} is always positive; {@link #type} carries the direction. Summing a
 * column of mixed-sign amounts is the classic way to get a dashboard that quietly reports
 * the wrong balance, so the sign lives in exactly one place.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Null when uncategorised, or when the category was deleted out from under it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryType type;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Column(length = 120)
    private String merchant;

    /**
     * The calendar day the money moved — deliberately a date, not an instant. A purchase
     * made at 23:00 in Algiers belongs to that day regardless of where the reader is.
     */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The amount as it contributes to a balance: positive for income, negative for expense. */
    public BigDecimal signedAmount() {
        return type == EntryType.INCOME ? amount : amount.negate();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Transaction.class.hashCode();
    }
}
