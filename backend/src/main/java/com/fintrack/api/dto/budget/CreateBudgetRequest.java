package com.fintrack.api.dto.budget;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Creates a budget for one month, optionally with its items in the same call - setting a
 * month's budget is one user action, and splitting it across N+1 requests would leave a
 * half-configured budget behind if the client stopped partway.
 */
public record CreateBudgetRequest(

        @NotNull(message = "Year is required")
        @Min(value = 2000, message = "Year must be 2000 or later")
        @Max(value = 2100, message = "Year must be 2100 or earlier")
        Integer year,

        @NotNull(message = "Month is required")
        @Min(value = 1, message = "Month must be between 1 and 12")
        @Max(value = 12, message = "Month must be between 1 and 12")
        Integer month,

        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code, e.g. DZD")
        String currency,

        @Valid
        List<BudgetItemRequest> items
) {}
