package com.fintrack.api.controller;

import com.fintrack.api.dto.common.PageResponse;
import com.fintrack.api.dto.transaction.CreateTransactionRequest;
import com.fintrack.api.dto.transaction.TransactionFilter;
import com.fintrack.api.dto.transaction.ReceiptResponse;
import com.fintrack.api.dto.transaction.TransactionResponse;
import com.fintrack.api.dto.transaction.UpdateTransactionRequest;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.service.CsvExportService;
import com.fintrack.api.service.ReceiptService;
import com.fintrack.api.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Income and expense entries")
public class TransactionController {

    private final TransactionService transactionService;
    private final CsvExportService csvExportService;
    private final ReceiptService receiptService;

    @GetMapping
    @Operation(summary = "List transactions",
            description = "All filters are optional and combine with AND. Results are always "
                    + "scoped to the caller. Sort with ?sort=occurredOn,desc.")
    public PageResponse<TransactionResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Optional filters") TransactionFilter filter,
            // Capped at 200 so a client cannot ask for the entire table in one response.
            @PageableDefault(size = 25, sort = "occurredOn", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return transactionService.list(principal.id(), filter, pageable);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "Export matching transactions as CSV",
            description = "Accepts the same filters as the list endpoint, so the file matches "
                    + "exactly what was on screen. UTF-8 with a BOM for Excel; capped at 50,000 rows.")
    public ResponseEntity<byte[]> exportCsv(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @Parameter(description = "Same filters as the list endpoint")
                                            TransactionFilter filter) {
        byte[] csv = csvExportService.export(principal.id(), filter);

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                // attachment, so the browser saves it rather than rendering it as a page.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(csvExportService.fileName(filter))
                                .build().toString())
                .body(csv);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one transaction")
    public TransactionResponse get(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @PathVariable UUID id) {
        return transactionService.get(principal.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a transaction",
            description = "Amount is always positive; type carries the direction. Date defaults "
                    + "to today and currency to the account's base currency.")
    public TransactionResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @Valid @RequestBody CreateTransactionRequest request) {
        return transactionService.create(principal.id(), request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a transaction",
            description = "Type is immutable. Use clearCategory=true to remove a category, since "
                    + "a null categoryId means 'unchanged'.")
    public TransactionResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @PathVariable UUID id,
                                      @Valid @RequestBody UpdateTransactionRequest request) {
        return transactionService.update(principal.id(), id, request);
    }

    @PostMapping(value = "/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach a receipt to a transaction",
            description = "JPEG, PNG, WebP or PDF, up to 5 MB. The type is detected from the "
                    + "file's own bytes, not the Content-Type header. Uploading again replaces "
                    + "the previous receipt.")
    public ReceiptResponse uploadReceipt(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @PathVariable UUID id,
                                         @RequestParam("file") MultipartFile file) {
        return receiptService.upload(principal.id(), id, file);
    }

    @GetMapping("/{id}/receipt")
    @Operation(summary = "Get a short-lived link to the receipt",
            description = "Returns a presigned URL valid for ten minutes. The bucket itself is "
                    + "private; a receipt is a financial document and is never publicly readable.")
    public ReceiptResponse getReceipt(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @PathVariable UUID id) {
        return receiptService.get(principal.id(), id);
    }

    @DeleteMapping("/{id}/receipt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the receipt, keeping the transaction")
    public void deleteReceipt(@AuthenticationPrincipal AuthenticatedUser principal,
                              @PathVariable UUID id) {
        receiptService.delete(principal.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a transaction")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID id) {
        transactionService.delete(principal.id(), id);
    }
}
