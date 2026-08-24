package com.fintrack.api.repository;

import com.fintrack.api.model.EntryType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Closed projection for "spend grouped by category", used by the dashboard.
 * Spring Data maps the constructor arguments positionally from the JPQL SELECT.
 */
public record CategoryTotal(
        UUID categoryId,
        String categoryName,
        String categoryColor,
        EntryType type,
        BigDecimal total,
        long transactionCount
) {}
