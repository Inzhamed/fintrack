package com.fintrack.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** A recurring payment the user wants to be reminded about. */
@Entity
@Table(name = "bills")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Recurrence recurrence;

    @Column(name = "due_day", nullable = false)
    private int dueDay;

    /** Only meaningful for {@link Recurrence#YEARLY}. */
    @Column(name = "due_month")
    private Integer dueMonth;

    @Column(name = "remind_days_before", nullable = false)
    @Builder.Default
    private int remindDaysBefore = 3;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * The due date a reminder was last sent for.
     * <p>
     * Deliberately the occurrence rather than a "sent at" timestamp: comparing against the
     * due date is what makes the scheduler idempotent. A job that runs twice, or catches up
     * after the service was down, still sends one reminder per occurrence.
     */
    @Column(name = "last_reminded_for")
    private LocalDate lastRemindedFor;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The next date this bill falls due, on or after {@code from}.
     * <p>
     * The day is clamped to the length of the target month, so a bill due on the 31st lands
     * on the 30th in November and the 28th in February rather than being skipped or throwing.
     */
    public LocalDate nextDueOnOrAfter(LocalDate from) {
        if (recurrence == Recurrence.YEARLY) {
            LocalDate candidate = clampedTo(YearMonth.of(from.getYear(), dueMonth));
            return candidate.isBefore(from)
                    ? clampedTo(YearMonth.of(from.getYear() + 1, dueMonth))
                    : candidate;
        }

        YearMonth month = YearMonth.from(from);
        LocalDate candidate = clampedTo(month);
        return candidate.isBefore(from) ? clampedTo(month.plusMonths(1)) : candidate;
    }

    private LocalDate clampedTo(YearMonth month) {
        return month.atDay(Math.min(dueDay, month.lengthOfMonth()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bill other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Bill.class.hashCode();
    }
}
