package com.fintrack.api.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Headline figures for a period.
 *
 * @param balance income minus expense. Negative means the period spent more than it earned,
 *                which is the number the whole app exists to surface.
 * @param savingsRate share of income kept, as a percentage to one decimal. Null rather than
 *                    zero when there was no income, because "saved 0% of nothing" is a
 *                    different statement from "saved none of what you earned".
 */
public record SummaryResponse(
        LocalDate from,
        LocalDate to,
        String currency,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        BigDecimal savingsRate,
        long incomeCount,
        long expenseCount,
        BigDecimal averageDailyExpense
) {}
