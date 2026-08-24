package com.fintrack.api.controller;

import com.fintrack.api.dto.budget.*;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.service.BudgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Budgets", description = "Monthly per-category spending limits")
public class BudgetController {

    private final BudgetService budgetService;

    @GetMapping("/budgets")
    @Operation(summary = "List every budget, newest month first")
    public List<BudgetResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return budgetService.listAll(principal.id());
    }

    @GetMapping("/budgets/{year}/{month}")
    @Operation(summary = "Fetch one month's budget with progress",
            description = "Each item reports spend, remaining, percentage and status. Totals "
                    + "also surface uncategorised and unbudgeted spend, so overspending "
                    + "outside the tracked lines cannot hide.")
    public BudgetResponse getByPeriod(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @PathVariable int year,
                                      @PathVariable int month) {
        return budgetService.getByPeriod(principal.id(), year, month);
    }

    @PostMapping("/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a month's budget, optionally with its items")
    public BudgetResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Valid @RequestBody CreateBudgetRequest request) {
        return budgetService.create(principal.id(), request);
    }

    @PostMapping("/budgets/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a category limit to a budget",
            description = "Only expense categories can be budgeted.")
    public BudgetResponse addItem(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody BudgetItemRequest request) {
        return budgetService.addItem(principal.id(), id, request);
    }

    @PatchMapping("/budget-items/{id}")
    @Operation(summary = "Update a limit or its alert threshold")
    public BudgetItemResponse updateItem(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @PathVariable UUID id,
                                         @Valid @RequestBody UpdateBudgetItemRequest request) {
        return budgetService.updateItem(principal.id(), id, request);
    }

    @DeleteMapping("/budget-items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a category limit from its budget")
    public void deleteItem(@AuthenticationPrincipal AuthenticatedUser principal,
                           @PathVariable UUID id) {
        budgetService.deleteItem(principal.id(), id);
    }

    @DeleteMapping("/budgets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a budget and all of its items")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID id) {
        budgetService.delete(principal.id(), id);
    }
}
