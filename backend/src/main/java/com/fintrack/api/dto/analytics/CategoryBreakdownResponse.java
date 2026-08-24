package com.fintrack.api.dto.analytics;

import com.fintrack.api.model.EntryType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Totals grouped by category, largest first - the pie chart on the dashboard.
 *
 * @param slices includes an "Uncategorised" entry when such transactions exist, so no
 *               spend is missing from the breakdown. That entry carries no categoryId,
 *               since the API omits null fields rather than emitting them. Shares are rounded to
 *               one decimal and so may total slightly under or over 100; {@code total}
 *               is the authoritative figure, not the sum of the shares.
 */
public record CategoryBreakdownResponse(
        LocalDate from,
        LocalDate to,
        EntryType type,
        BigDecimal total,
        List<Slice> slices
) {
    /**
     * @param share percentage of {@code total}, to one decimal place
     */
    public record Slice(
            UUID categoryId,
            String categoryName,
            String color,
            BigDecimal amount,
            BigDecimal share,
            long transactionCount
    ) {}
}
