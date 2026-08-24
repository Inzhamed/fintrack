package com.fintrack.api.model;

/**
 * Whether money moves in or out.
 * <p>
 * Shared by {@link Category} and {@link Transaction} so that a transaction can never be
 * filed under a category of the opposite direction — an expense against "Salary" is a
 * data-entry mistake the domain should be able to reject.
 */
public enum EntryType {
    EXPENSE,
    INCOME
}
