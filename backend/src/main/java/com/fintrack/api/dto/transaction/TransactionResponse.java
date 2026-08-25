package com.fintrack.api.dto.transaction;

import com.fintrack.api.model.EntryType;
import com.fintrack.api.model.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The category is inlined as a small summary rather than a bare id, so a list view can
 * render a coloured chip per row without a second request or an N+1 lookup client-side.
 */
public record TransactionResponse(
        UUID id,
        EntryType type,
        BigDecimal amount,
        String currency,
        String description,
        String merchant,
        LocalDate occurredOn,
        CategorySummary category,
        boolean hasReceipt,
        Instant createdAt,
        Instant updatedAt
) {
    public record CategorySummary(UUID id, String name, String color, String icon) {}

    public static TransactionResponse from(Transaction transaction) {
        var category = transaction.getCategory();
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getMerchant(),
                transaction.getOccurredOn(),
                category == null ? null : new CategorySummary(
                        category.getId(), category.getName(),
                        category.getColor(), category.getIcon()),
                transaction.getReceiptKey() != null,
                transaction.getCreatedAt(),
                transaction.getUpdatedAt());
    }
}
