package com.fintrack.api.service;

import com.fintrack.api.config.StorageConfig.StorageProperties;
import com.fintrack.api.dto.transaction.ReceiptResponse;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.exception.ErrorCode;
import com.fintrack.api.model.Transaction;
import com.fintrack.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Receipt images attached to a transaction.
 *
 * <h2>Upload through the API, download by presigned URL</h2>
 * Asymmetric on purpose. Uploads are rare, small, and the one place untrusted bytes enter the
 * system, so they are worth proxying in order to validate them. Downloads are the frequent
 * path and gain nothing from streaming through the application, so the client is handed a
 * short-lived signed URL and fetches the object directly.
 *
 * <h2>The bucket is never public</h2>
 * A receipt is a financial document. Object keys are unguessable, but obscurity is not access
 * control - the bucket stays private and every read goes through a URL signed for one object,
 * for a few minutes, after an ownership check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptService {

    /** Large enough for a phone photo, small enough not to be a denial-of-service vector. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    /** Long enough to load an image, short enough that a leaked URL expires quickly. */
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(10);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storage;
    private final TransactionRepository transactionRepository;

    @Transactional
    public ReceiptResponse upload(UUID userId, UUID transactionId, MultipartFile file) {
        Transaction transaction = ownedOrFail(userId, transactionId);

        if (file == null || file.isEmpty()) {
            throw ApiException.validation("No file was uploaded", Map.of());
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.validation(
                    "Receipt must be 5 MB or smaller",
                    Map.of("sizeBytes", file.getSize(), "maxBytes", MAX_BYTES));
        }

        byte[] content = read(file);
        // Sniffed from the bytes, not taken from the Content-Type header. The header is
        // supplied by the client and a caller can claim image/png for anything at all; the
        // magic number is what the file actually is.
        String contentType = detectContentType(content)
                .orElseThrow(() -> ApiException.validation(
                        "Receipt must be a JPEG, PNG, WebP or PDF",
                        Map.of("allowed", ALLOWED_TYPES)));

        // Keyed by user, so a listing of the bucket cannot be walked to find one person's
        // receipts, and a random component so a key is never guessable from the ids.
        String key = "receipts/%s/%s-%s".formatted(
                userId, transactionId, UUID.randomUUID().toString().replace("-", ""));

        String previousKey = transaction.getReceiptKey();

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(storage.bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        // Stored so a later download can suggest the original name without
                        // trusting whatever the client sends at that point.
                        .metadata(Map.of("original-filename", sanitise(file.getOriginalFilename())))
                        .build(),
                RequestBody.fromBytes(content));

        transaction.setReceiptKey(key);
        transaction.setReceiptFilename(sanitise(file.getOriginalFilename()));
        transaction.setReceiptContentType(contentType);
        transaction.setReceiptSizeBytes((long) content.length);

        // The replaced object is removed only after the new one is safely written, so a
        // failed upload never leaves the transaction pointing at a deleted file.
        if (previousKey != null) {
            deleteQuietly(previousKey);
        }

        return ReceiptResponse.from(transaction, presignedUrl(key));
    }

    @Transactional(readOnly = true)
    public ReceiptResponse get(UUID userId, UUID transactionId) {
        Transaction transaction = ownedOrFail(userId, transactionId);
        if (transaction.getReceiptKey() == null) {
            throw ApiException.notFound("Receipt", transactionId);
        }
        return ReceiptResponse.from(transaction, presignedUrl(transaction.getReceiptKey()));
    }

    @Transactional
    public void delete(UUID userId, UUID transactionId) {
        Transaction transaction = ownedOrFail(userId, transactionId);
        String key = transaction.getReceiptKey();
        if (key == null) {
            throw ApiException.notFound("Receipt", transactionId);
        }

        transaction.setReceiptKey(null);
        transaction.setReceiptFilename(null);
        transaction.setReceiptContentType(null);
        transaction.setReceiptSizeBytes(null);

        deleteQuietly(key);
    }

    // --- internals ---------------------------------------------------------------------

    private String presignedUrl(String key) {
        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(DOWNLOAD_TTL)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(storage.bucket())
                                .key(key)
                                .build())
                        .build())
                .url()
                .toString();
    }

    /**
     * Removes an object without letting the failure escape.
     * <p>
     * The database row is the source of truth for whether a receipt exists. If the delete
     * fails the object is orphaned, which costs storage; failing the request instead would
     * leave the user unable to remove a receipt at all.
     */
    private void deleteQuietly(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(storage.bucket())
                    .key(key)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Could not delete receipt object {}; it is now orphaned", key, ex);
        }
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Could not read the uploaded file");
        }
    }

    /**
     * Identifies the file from its leading bytes.
     * <p>
     * Four formats, four signatures - a dependency for this would be more surface than the
     * eight lines it replaces.
     */
    private static java.util.Optional<String> detectContentType(byte[] content) {
        if (content.length < 12) {
            return java.util.Optional.empty();
        }
        String head = HexFormat.of().formatHex(content, 0, 12).toUpperCase();

        if (head.startsWith("FFD8FF")) return java.util.Optional.of("image/jpeg");
        if (head.startsWith("89504E470D0A1A0A")) return java.util.Optional.of("image/png");
        if (head.startsWith("25504446")) return java.util.Optional.of("application/pdf");
        // WebP is a RIFF container: "RIFF" then four size bytes then "WEBP".
        if (head.startsWith("52494646") && head.substring(16).startsWith("57454250")) {
            return java.util.Optional.of("image/webp");
        }
        return java.util.Optional.empty();
    }

    /**
     * Strips any path from the client-supplied name and bounds its length.
     * <p>
     * The name is only ever echoed back, never used to build a path, but a value containing
     * {@code ../} has no business being stored and would be a trap for any future code that
     * did treat it as a path.
     */
    private static String sanitise(String filename) {
        if (filename == null || filename.isBlank()) {
            return "receipt";
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() <= 200 ? base : base.substring(base.length() - 200);
    }

    private Transaction ownedOrFail(UUID userId, UUID transactionId) {
        return transactionRepository.findOwned(transactionId, userId)
                .orElseThrow(() -> ApiException.notFound("Transaction", transactionId));
    }
}
