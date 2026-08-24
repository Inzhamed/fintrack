package com.fintrack.api.repository;

import com.fintrack.api.model.EntryType;

import java.math.BigDecimal;

/**
 * One bucket of the cashflow series: a calendar month, a direction, and the total.
 * <p>
 * Grouped with HQL's {@code year()}/{@code month()} rather than a native
 * {@code date_trunc}, so the query stays portable and Hibernate keeps mapping it to the
 * record constructor.
 */
public record PeriodTotal(int year, int month, EntryType type, BigDecimal total, long count) {}
