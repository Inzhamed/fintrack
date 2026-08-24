package com.fintrack.api.dto.budget;

import com.fintrack.api.model.BudgetItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One category's limit and how the month is tracking against it.
 * <p>
 * The derived figures - spent, remaining, percentage, status - are computed server-side
 * rather than left to each client. Three clients deriving "82%" independently is three
 * chances to round it differently from the alert that fires at 80%.
 *
 * @param spent     total expense against this category within the budget's month
 * @param remaining limit minus spent; negative once the limit is passed
 * @param percentUsed spend as a percentage of the limit, one decimal place (82.5 = 82.5%)
 */
public record BudgetItemResponse(
        UUID id,
        CategoryRef category,
        BigDecimal limitAmount,
        BigDecimal alertThreshold,
        BigDecimal spent,
        BigDecimal remaining,
        BigDecimal percentUsed,
        BudgetStatus status
) {
    public record CategoryRef(UUID id, String name, String color, String icon) {}

    public static BudgetItemResponse of(BudgetItem item, BigDecimal spent) {
        BigDecimal limit = item.getLimitAmount();
        BigDecimal actualSpent = spent == null ? BigDecimal.ZERO : spent;

        BigDecimal percent = limit.signum() == 0
                ? BigDecimal.ZERO
                : actualSpent.multiply(BigDecimal.valueOf(100))
                        .divide(limit, 1, RoundingMode.HALF_UP);

        var category = item.getCategory();

        return new BudgetItemResponse(
                item.getId(),
                new CategoryRef(category.getId(), category.getName(),
                        category.getColor(), category.getIcon()),
                limit,
                item.getAlertThreshold(),
                actualSpent,
                limit.subtract(actualSpent),
                percent,
                statusOf(actualSpent, limit, item.alertAmount()));
    }

    private static BudgetStatus statusOf(BigDecimal spent, BigDecimal limit, BigDecimal alertAt) {
        if (spent.compareTo(limit) > 0) {
            return BudgetStatus.EXCEEDED;
        }
        // Deliberately >= : a threshold of 0.80 should fire *at* 80%, not just past it.
        if (spent.compareTo(alertAt) >= 0) {
            return BudgetStatus.WARNING;
        }
        return BudgetStatus.ON_TRACK;
    }
}
