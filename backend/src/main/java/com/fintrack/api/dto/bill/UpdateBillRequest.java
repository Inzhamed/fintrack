package com.fintrack.api.dto.bill;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PATCH semantics: a null field means "leave unchanged".
 * <p>
 * Recurrence is not updatable - switching a bill between monthly and yearly changes which of
 * dueDay/dueMonth are required, and the two would have to move together. Deleting and
 * recreating makes that explicit.
 */
public record UpdateBillRequest(

        @Size(min = 1, max = 120, message = "Name must be between 1 and 120 characters")
        String name,

        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Min(value = 1, message = "Due day must be between 1 and 31")
        @Max(value = 31, message = "Due day must be between 1 and 31")
        Integer dueDay,

        @Min(value = 1, message = "Due month must be between 1 and 12")
        @Max(value = 12, message = "Due month must be between 1 and 12")
        Integer dueMonth,

        @Min(value = 0, message = "Remind days must be between 0 and 30")
        @Max(value = 30, message = "Remind days must be between 0 and 30")
        Integer remindDaysBefore,

        Boolean active,

        UUID categoryId,

        /** A null categoryId means "unchanged", so removing one needs its own flag. */
        Boolean clearCategory
) {
    public boolean wantsCategoryCleared() {
        return Boolean.TRUE.equals(clearCategory);
    }
}
