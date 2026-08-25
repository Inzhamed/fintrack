package com.fintrack.api.dto.bill;

import com.fintrack.api.model.Bill;
import com.fintrack.api.model.Recurrence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * @param nextDueOn   computed, not stored - a bill has a rule, not a date, and the answer
 *                    depends on when you ask
 * @param daysUntilDue negative once the due date has passed without the bill being marked paid
 */
public record BillResponse(
        UUID id,
        String name,
        BigDecimal amount,
        String currency,
        Recurrence recurrence,
        int dueDay,
        Integer dueMonth,
        int remindDaysBefore,
        boolean active,
        CategoryRef category,
        LocalDate nextDueOn,
        long daysUntilDue
) {
    public record CategoryRef(UUID id, String name, String color) {}

    public static BillResponse from(Bill bill, LocalDate today) {
        LocalDate nextDue = bill.nextDueOnOrAfter(today);
        var category = bill.getCategory();

        return new BillResponse(
                bill.getId(),
                bill.getName(),
                bill.getAmount(),
                bill.getCurrency(),
                bill.getRecurrence(),
                bill.getDueDay(),
                bill.getDueMonth(),
                bill.getRemindDaysBefore(),
                bill.isActive(),
                category == null ? null
                        : new CategoryRef(category.getId(), category.getName(), category.getColor()),
                nextDue,
                ChronoUnit.DAYS.between(today, nextDue));
    }
}
