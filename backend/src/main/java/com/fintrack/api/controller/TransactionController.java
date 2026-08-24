package com.fintrack.api.controller;

import com.fintrack.api.dto.common.PageResponse;
import com.fintrack.api.dto.transaction.CreateTransactionRequest;
import com.fintrack.api.dto.transaction.TransactionFilter;
import com.fintrack.api.dto.transaction.TransactionResponse;
import com.fintrack.api.dto.transaction.UpdateTransactionRequest;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Income and expense entries")
public class TransactionController {

    private final TransactionService transactionService;

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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a transaction")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID id) {
        transactionService.delete(principal.id(), id);
    }
}
