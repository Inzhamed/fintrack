package com.fintrack.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One user's plan for one calendar month, holding a per-category limit in each
 * {@link BudgetItem}. At most one budget exists per user per month.
 */
@Entity
@Table(name = "budgets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * Orphan removal so that dropping an item from this list deletes the row. The budget
     * is the aggregate root; items have no meaning outside it.
     */
    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BudgetItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public YearMonth period() {
        return YearMonth.of(periodYear, periodMonth);
    }

    public LocalDate periodStart() {
        return period().atDay(1);
    }

    public LocalDate periodEnd() {
        return period().atEndOfMonth();
    }

    /** Keeps both sides of the association consistent. */
    public void addItem(BudgetItem item) {
        items.add(item);
        item.setBudget(this);
    }

    public void removeItem(BudgetItem item) {
        items.remove(item);
        item.setBudget(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Budget other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Budget.class.hashCode();
    }
}
