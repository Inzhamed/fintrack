package com.fintrack.api.dto.notification;

import com.fintrack.api.dto.budget.BudgetStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A message pushed to one user over their WebSocket session.
 *
 * @param type    what happened, so the client can branch without parsing the text
 * @param title   short heading, ready to display
 * @param message the sentence a person reads
 * @param data    type-specific detail; null for messages that carry none
 */
public record NotificationMessage(
        Type type,
        String title,
        String message,
        Object data,
        Instant timestamp
) {
    /** Mirrors {@link com.fintrack.api.model.NotificationType}; the two are converted by name. */
    public enum Type {
        /** Spending crossed a budget item's alert threshold, or passed the limit outright. */
        BUDGET_THRESHOLD,
        /** A recurring bill is coming due. */
        BILL_DUE
    }

    /**
     * Detail for a budget alert.
     *
     * @param status WARNING when past the threshold, EXCEEDED when past the limit itself
     */
    public record BudgetAlert(
            UUID budgetItemId,
            UUID categoryId,
            String categoryName,
            String categoryColor,
            BigDecimal spent,
            BigDecimal limitAmount,
            BigDecimal percentUsed,
            BudgetStatus status
    ) {}

    public static NotificationMessage budgetThreshold(BudgetAlert alert, String currency) {
        boolean exceeded = alert.status() == BudgetStatus.EXCEEDED;

        String title = exceeded
                ? "Over budget: " + alert.categoryName()
                : "Approaching your " + alert.categoryName() + " budget";

        String message = exceeded
                ? "You have spent %s of your %s limit for %s."
                        .formatted(money(alert.spent(), currency),
                                money(alert.limitAmount(), currency), alert.categoryName())
                : "You have used %.0f%% of your %s budget for %s."
                        .formatted(alert.percentUsed(), money(alert.limitAmount(), currency),
                                alert.categoryName());

        return new NotificationMessage(
                Type.BUDGET_THRESHOLD, title, message, alert, Instant.now());
    }

    private static String money(BigDecimal amount, String currency) {
        return "%s %s".formatted(amount.toPlainString(), currency);
    }
}
