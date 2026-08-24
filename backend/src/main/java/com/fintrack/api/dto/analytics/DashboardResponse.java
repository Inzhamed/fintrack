package com.fintrack.api.dto.analytics;

import com.fintrack.api.dto.budget.BudgetItemResponse;
import com.fintrack.api.dto.transaction.TransactionResponse;

import java.util.List;

/**
 * Everything the landing screen needs, in one request.
 * <p>
 * The parts are all available individually, but a dashboard that assembles itself from five
 * round trips shows five separate loading states and can render a summary from one moment
 * beside a chart from another. One call keeps the whole screen consistent.
 *
 * @param alerts budget items currently at WARNING or EXCEEDED, worst first. Empty when the
 *               month is on track or no budget is set.
 */
public record DashboardResponse(
        SummaryResponse currentMonth,
        SummaryResponse previousMonth,
        CategoryBreakdownResponse topExpenseCategories,
        CashflowResponse cashflow,
        List<TransactionResponse> recentTransactions,
        List<BudgetItemResponse> alerts,
        boolean budgetSet
) {}
