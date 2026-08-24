package com.fintrack.api.dto.budget;

/** How a budget item is tracking against its limit. */
public enum BudgetStatus {
    /** Below the alert threshold. */
    ON_TRACK,
    /** At or past the alert threshold (80% by default) but still within the limit. */
    WARNING,
    /** Spend has passed the limit. */
    EXCEEDED
}
