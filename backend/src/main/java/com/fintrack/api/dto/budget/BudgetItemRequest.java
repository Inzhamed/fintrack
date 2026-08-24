package com.fintrack.api.dto.budget;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param alertThreshold fraction of the limit at which to warn, e.g. 0.80 for 80%.
 *                       Defaults to 0.80 when omitted.
 */
public record BudgetItemRequest(

        @NotNull(message = "Category is required")
        UUID categoryId,

        @NotNull(message = "Limit is required")
        @DecimalMin(value = "0.01", message = "Limit must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Limit must have at most 2 decimal places")
        BigDecimal limitAmount,

        @DecimalMin(value = "0.01", message = "Alert threshold must be between 0.01 and 1.00")
        @DecimalMax(value = "1.00", message = "Alert threshold must be between 0.01 and 1.00")
        @Digits(integer = 1, fraction = 2, message = "Alert threshold must have at most 2 decimal places")
        BigDecimal alertThreshold
) {}
