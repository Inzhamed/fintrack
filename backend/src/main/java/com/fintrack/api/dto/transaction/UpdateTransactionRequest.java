package com.fintrack.api.dto.transaction;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * PATCH semantics: a null field means "leave unchanged".
 * <p>
 * {@code type} is absent by design. Flipping an expense into income changes what every
 * total means; deleting and re-creating makes that intent explicit.
 * <p>
 * Clearing the category is therefore expressed by {@code clearCategory}, since a null
 * {@code categoryId} cannot mean both "unchanged" and "remove".
 */
public record UpdateTransactionRequest(

        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code, e.g. DZD")
        String currency,

        UUID categoryId,

        // Boolean, not boolean: in a PATCH body "absent" is a third state distinct from
        // true and false, and a primitive cannot carry it. Jackson 3 also enables
        // FAIL_ON_NULL_FOR_PRIMITIVES by default, so an omitted primitive is a 400.
        Boolean clearCategory,

        @Size(max = 255, message = "Description must be at most 255 characters")
        String description,

        @Size(max = 120, message = "Merchant must be at most 120 characters")
        String merchant,

        @PastOrPresent(message = "Date cannot be in the future")
        LocalDate occurredOn
) {
    public boolean wantsCategoryCleared() {
        return Boolean.TRUE.equals(clearCategory);
    }
}
