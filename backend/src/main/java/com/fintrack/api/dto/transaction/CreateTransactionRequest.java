package com.fintrack.api.dto.transaction;

import com.fintrack.api.model.EntryType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param amount      always positive; direction comes from {@code type}. Two decimal places,
 *                    matching NUMERIC(14,2) - more precision would be silently rounded away
 *                    by the database rather than rejected.
 * @param occurredOn  defaults to today when omitted
 * @param currency    defaults to the user's base currency when omitted
 */
public record CreateTransactionRequest(

        @NotNull(message = "Type is required and must be EXPENSE or INCOME")
        EntryType type,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code, e.g. DZD")
        String currency,

        UUID categoryId,

        @Size(max = 255, message = "Description must be at most 255 characters")
        String description,

        @Size(max = 120, message = "Merchant must be at most 120 characters")
        String merchant,

        @PastOrPresent(message = "Date cannot be in the future")
        LocalDate occurredOn
) {}
