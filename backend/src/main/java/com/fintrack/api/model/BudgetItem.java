package com.fintrack.api.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/** A spending ceiling for one category within one {@link Budget}. */
@Entity
@Table(name = "budget_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetItem {

    /** Warn at 80% of the limit unless the user says otherwise. */
    public static final BigDecimal DEFAULT_ALERT_THRESHOLD = new BigDecimal("0.80");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "budget_id", nullable = false)
    private Budget budget;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "limit_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal limitAmount;

    /** Fraction of {@link #limitAmount} at which to alert, e.g. 0.80 for 80%. */
    @Column(name = "alert_threshold", nullable = false, precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal alertThreshold = DEFAULT_ALERT_THRESHOLD;

    /** The absolute spend at which this item should raise a warning. */
    public BigDecimal alertAmount() {
        return limitAmount.multiply(alertThreshold);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BudgetItem other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return BudgetItem.class.hashCode();
    }
}
