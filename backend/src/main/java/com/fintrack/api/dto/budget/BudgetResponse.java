package com.fintrack.api.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A month's budget with every item's progress, plus the month's roll-up.
 *
 * @param uncategorisedSpend expense in this month filed under no category, and therefore
 *                           counted against no item. Surfaced explicitly because a budget
 *                           that silently ignores part of the spending is worse than no
 *                           budget - the totals would look healthy while money leaked.
 * @param unbudgetedSpend    expense in categories that have no item in this budget
 */
public record BudgetResponse(
        UUID id,
        int year,
        int month,
        String currency,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<BudgetItemResponse> items,
        Totals totals
) {
    /**
     * @param totalLimit   sum of every item's limit
     * @param totalSpent   spend counted against those items
     * @param totalIncome  income recorded in the month, for context against the outgoings
     */
    public record Totals(
            BigDecimal totalLimit,
            BigDecimal totalSpent,
            BigDecimal totalRemaining,
            BigDecimal percentUsed,
            BigDecimal uncategorisedSpend,
            BigDecimal unbudgetedSpend,
            BigDecimal totalIncome,
            int itemsWarning,
            int itemsExceeded
    ) {}
}
