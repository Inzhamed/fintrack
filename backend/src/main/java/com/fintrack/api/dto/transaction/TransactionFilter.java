package com.fintrack.api.dto.transaction;

import com.fintrack.api.model.EntryType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Query parameters for the transaction list endpoint. Bound from the query string, so
 * every field is optional and a null simply means "do not filter on this".
 *
 * @param uncategorised when true, returns only transactions with no category. Cannot be
 *                      combined meaningfully with {@code categoryId}, which the service rejects.
 */
public record TransactionFilter(

        @Schema(description = "Earliest date, inclusive", example = "2026-08-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @Schema(description = "Latest date, inclusive", example = "2026-08-31")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to,

        @Schema(description = "EXPENSE or INCOME")
        EntryType type,

        UUID categoryId,

        @Schema(description = "Only transactions with no category")
        Boolean uncategorised,

        @Schema(description = "Minimum amount, inclusive")
        BigDecimal minAmount,

        @Schema(description = "Maximum amount, inclusive")
        BigDecimal maxAmount,

        @Schema(description = "Case-insensitive substring of description or merchant")
        String search
) {
    public boolean wantsUncategorised() {
        return Boolean.TRUE.equals(uncategorised);
    }
}
