package com.fintrack.api.model;

/** The kinds of notification the application produces. */
public enum NotificationType {
    /** Spending crossed a budget item's threshold or passed its limit. */
    BUDGET_THRESHOLD,
    /** A recurring bill is coming due. */
    BILL_DUE
}
