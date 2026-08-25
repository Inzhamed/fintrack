package com.fintrack.api.dto.transaction;

import com.fintrack.api.model.Transaction;

import java.time.Instant;

/**
 * @param url a presigned link to the object, valid for a few minutes. Regenerated on every
 *            request rather than stored, so a URL that leaks stops working shortly after.
 */
public record ReceiptResponse(
        String filename,
        String contentType,
        long sizeBytes,
        String url,
        Instant expiresAt
) {
    public static ReceiptResponse from(Transaction transaction, String url) {
        return new ReceiptResponse(
                transaction.getReceiptFilename(),
                transaction.getReceiptContentType(),
                transaction.getReceiptSizeBytes() == null ? 0 : transaction.getReceiptSizeBytes(),
                url,
                Instant.now().plusSeconds(600));
    }
}
