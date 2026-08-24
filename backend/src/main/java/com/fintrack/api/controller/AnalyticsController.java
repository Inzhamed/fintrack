package com.fintrack.api.controller;

import com.fintrack.api.dto.analytics.*;
import com.fintrack.api.model.EntryType;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Read-only aggregates. Every range defaults to the current calendar month when
 * {@code from} and {@code to} are omitted.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Dashboard figures, breakdowns and trends")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/dashboard")
    @Operation(summary = "Everything the landing screen needs, in one request",
            description = "This month and last month side by side, spend by category, a "
                    + "six-month cashflow trend, recent activity, and any budget alerts.")
    public DashboardResponse dashboard(@AuthenticationPrincipal AuthenticatedUser principal) {
        return analyticsService.dashboard(principal.id(), LocalDate.now());
    }

    @GetMapping("/analytics/summary")
    @Operation(summary = "Income, expense, balance and savings rate for a period")
    public SummaryResponse summary(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(example = "2026-08-01") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(example = "2026-08-31") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.summary(principal.id(), from, to);
    }

    @GetMapping("/analytics/by-category")
    @Operation(summary = "Totals grouped by category, largest first",
            description = "Defaults to EXPENSE. Uncategorised transactions appear as their own "
                    + "slice, carrying no categoryId, so no spend is left out of the breakdown.")
    public CategoryBreakdownResponse byCategory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EntryType type) {
        return analyticsService.byCategory(principal.id(), from, to, type);
    }

    @GetMapping("/analytics/cashflow")
    @Operation(summary = "Income against expense per calendar month",
            description = "Months with no activity are returned as zeros rather than omitted.")
    public CashflowResponse cashflow(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.cashflow(principal.id(), from, to);
    }
}
