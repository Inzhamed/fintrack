package com.fintrack.api.dto.budget;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** PATCH semantics: a null field means "leave unchanged". Category is immutable. */
public record UpdateBudgetItemRequest(

        @DecimalMin(value = "0.01", message = "Limit must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Limit must have at most 2 decimal places")
        BigDecimal limitAmount,

        @DecimalMin(value = "0.01", message = "Alert threshold must be between 0.01 and 1.00")
        @DecimalMax(value = "1.00", message = "Alert threshold must be between 0.01 and 1.00")
        @Digits(integer = 1, fraction = 2, message = "Alert threshold must have at most 2 decimal places")
        BigDecimal alertThreshold
) {}
