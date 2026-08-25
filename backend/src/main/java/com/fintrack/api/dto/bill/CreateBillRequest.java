package com.fintrack.api.dto.bill;

import com.fintrack.api.model.Recurrence;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param dueMonth required for YEARLY, forbidden for MONTHLY - checked in the service, since
 *                 the rule spans two fields and a field-level annotation cannot see both
 */
public record CreateBillRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code, e.g. DZD")
        String currency,

        @NotNull(message = "Recurrence is required and must be MONTHLY or YEARLY")
        Recurrence recurrence,

        @NotNull(message = "Due day is required")
        @Min(value = 1, message = "Due day must be between 1 and 31")
        @Max(value = 31, message = "Due day must be between 1 and 31")
        Integer dueDay,

        @Min(value = 1, message = "Due month must be between 1 and 12")
        @Max(value = 12, message = "Due month must be between 1 and 12")
        Integer dueMonth,

        @Min(value = 0, message = "Remind days must be between 0 and 30")
        @Max(value = 30, message = "Remind days must be between 0 and 30")
        Integer remindDaysBefore,

        UUID categoryId
) {}
