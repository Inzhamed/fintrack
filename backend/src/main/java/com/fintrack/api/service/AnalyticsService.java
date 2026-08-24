package com.fintrack.api.service;

import com.fintrack.api.dto.analytics.*;
import com.fintrack.api.dto.budget.BudgetItemResponse;
import com.fintrack.api.dto.budget.BudgetStatus;
import com.fintrack.api.dto.transaction.TransactionResponse;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.model.Budget;
import com.fintrack.api.model.EntryType;
import com.fintrack.api.model.User;
import com.fintrack.api.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read-only aggregates over a user's transactions. */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** How many months of history the dashboard's trend chart covers. */
    private static final int CASHFLOW_MONTHS = 6;

    /** How many recent transactions the dashboard carries. */
    private static final int RECENT_LIMIT = 8;

    /** Cap on the reporting window, so one request cannot ask the database to scan a decade. */
    private static final long MAX_RANGE_DAYS = 366L * 5;

    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public SummaryResponse summary(UUID userId, LocalDate from, LocalDate to) {
        Range range = Range.of(from, to);
        return summaryFor(userId, range.from(), range.to(), currencyOf(userId));
    }

    @Transactional(readOnly = true)
    public CategoryBreakdownResponse byCategory(UUID userId, LocalDate from, LocalDate to,
                                                EntryType type) {
        Range range = Range.of(from, to);
        EntryType direction = type == null ? EntryType.EXPENSE : type;

        List<CategoryTotal> totals = transactionRepository.totalsByCategory(
                userId, direction, range.from(), range.to());

        BigDecimal total = totals.stream()
                .map(CategoryTotal::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CategoryBreakdownResponse.Slice> slices = totals.stream()
                .map(entry -> new CategoryBreakdownResponse.Slice(
                        entry.categoryId(),
                        // A null id is the uncategorised bucket. Naming it keeps the shares
                        // summing to 100 instead of quietly losing that spend.
                        entry.categoryId() == null ? "Uncategorised" : entry.categoryName(),
                        entry.categoryId() == null ? "#94a3b8" : entry.categoryColor(),
                        entry.total(),
                        percentageOf(entry.total(), total),
                        entry.transactionCount()))
                .toList();

        return new CategoryBreakdownResponse(range.from(), range.to(), direction, total, slices);
    }

    @Transactional(readOnly = true)
    public CashflowResponse cashflow(UUID userId, LocalDate from, LocalDate to) {
        Range range = Range.of(from, to);
        return cashflowFor(userId, range.from(), range.to(), currencyOf(userId));
    }

    /** Assembles the whole landing screen from one consistent snapshot. */
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID userId, LocalDate today) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        String currency = user.getBaseCurrency();

        YearMonth thisMonth = YearMonth.from(today);
        YearMonth lastMonth = thisMonth.minusMonths(1);

        SummaryResponse current = summaryFor(
                userId, thisMonth.atDay(1), thisMonth.atEndOfMonth(), currency);
        SummaryResponse previous = summaryFor(
                userId, lastMonth.atDay(1), lastMonth.atEndOfMonth(), currency);

        CategoryBreakdownResponse categories = byCategory(
                userId, thisMonth.atDay(1), thisMonth.atEndOfMonth(), EntryType.EXPENSE);

        CashflowResponse cashflow = cashflowFor(
                userId,
                thisMonth.minusMonths(CASHFLOW_MONTHS - 1L).atDay(1),
                thisMonth.atEndOfMonth(),
                currency);

        List<TransactionResponse> recent = transactionRepository
                .findRecent(userId, PageRequest.of(0, RECENT_LIMIT)).stream()
                .map(TransactionResponse::from)
                .toList();

        // Budget alerts for the current month, if a budget exists at all.
        Optional<Budget> budget = budgetRepository.findByPeriod(
                userId, thisMonth.getYear(), thisMonth.getMonthValue());

        List<BudgetItemResponse> alerts = budget
                .map(value -> alertsFor(value, categories))
                .orElseGet(List::of);

        return new DashboardResponse(
                current, previous, categories, cashflow, recent, alerts, budget.isPresent());
    }

    // --- internals ---------------------------------------------------------------------

    /**
     * Reuses the breakdown already computed for the current month rather than re-querying
     * spend per category, so the dashboard's alerts can never disagree with its own chart.
     */
    private List<BudgetItemResponse> alertsFor(Budget budget,
                                               CategoryBreakdownResponse breakdown) {
        Map<UUID, BigDecimal> spendByCategory = new HashMap<>();
        for (CategoryBreakdownResponse.Slice slice : breakdown.slices()) {
            if (slice.categoryId() != null) {
                spendByCategory.put(slice.categoryId(), slice.amount());
            }
        }

        return budget.getItems().stream()
                .map(item -> BudgetItemResponse.of(
                        item, spendByCategory.get(item.getCategory().getId())))
                .filter(item -> item.status() != BudgetStatus.ON_TRACK)
                // Worst first: EXCEEDED above WARNING, and within each, the biggest overrun.
                .sorted(Comparator
                        .comparing((BudgetItemResponse item) -> item.status() == BudgetStatus.EXCEEDED ? 0 : 1)
                        .thenComparing(BudgetItemResponse::percentUsed, Comparator.reverseOrder()))
                .toList();
    }

    private SummaryResponse summaryFor(UUID userId, LocalDate from, LocalDate to,
                                       String currency) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        long incomeCount = 0;
        long expenseCount = 0;

        for (TypeTotal total : transactionRepository.totalsByType(userId, from, to)) {
            if (total.type() == EntryType.INCOME) {
                income = total.total();
                incomeCount = total.count();
            } else {
                expense = total.total();
                expenseCount = total.count();
            }
        }

        // Null, not zero, when there was no income: "kept 0% of nothing" would be misleading.
        BigDecimal savingsRate = income.signum() == 0
                ? null
                : income.subtract(expense).multiply(BigDecimal.valueOf(100))
                        .divide(income, 1, RoundingMode.HALF_UP);

        long days = ChronoUnit.DAYS.between(from, to) + 1;
        BigDecimal averageDailyExpense = expense.divide(
                BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);

        return new SummaryResponse(
                from, to, currency,
                income, expense, income.subtract(expense), savingsRate,
                incomeCount, expenseCount, averageDailyExpense);
    }

    private CashflowResponse cashflowFor(UUID userId, LocalDate from, LocalDate to,
                                         String currency) {
        Map<YearMonth, BigDecimal> incomeByMonth = new HashMap<>();
        Map<YearMonth, BigDecimal> expenseByMonth = new HashMap<>();

        for (PeriodTotal total : transactionRepository.totalsByMonth(userId, from, to)) {
            YearMonth key = YearMonth.of(total.year(), total.month());
            if (total.type() == EntryType.INCOME) {
                incomeByMonth.merge(key, total.total(), BigDecimal::add);
            } else {
                expenseByMonth.merge(key, total.total(), BigDecimal::add);
            }
        }

        // Walk every month in the range so quiet months appear as zeros rather than gaps.
        List<CashflowResponse.Point> points = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;

        for (YearMonth month = YearMonth.from(from);
             !month.isAfter(YearMonth.from(to));
             month = month.plusMonths(1)) {

            BigDecimal income = incomeByMonth.getOrDefault(month, BigDecimal.ZERO);
            BigDecimal expense = expenseByMonth.getOrDefault(month, BigDecimal.ZERO);
            BigDecimal monthNet = income.subtract(expense);
            net = net.add(monthNet);

            points.add(new CashflowResponse.Point(
                    month.toString(), month.getYear(), month.getMonthValue(),
                    income, expense, monthNet));
        }

        return new CashflowResponse(from, to, currency, points, net);
    }

    private String currencyOf(UUID userId) {
        return userRepository.findById(userId)
                .map(User::getBaseCurrency)
                .orElseThrow(() -> ApiException.notFound("User", userId));
    }

    private static BigDecimal percentageOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /**
     * A validated reporting window. Defaults to the current calendar month, which is what
     * the dashboard asks for almost every time.
     */
    private record Range(LocalDate from, LocalDate to) {

        static Range of(LocalDate from, LocalDate to) {
            YearMonth thisMonth = YearMonth.now();
            LocalDate start = from == null ? thisMonth.atDay(1) : from;
            LocalDate end = to == null ? thisMonth.atEndOfMonth() : to;

            if (start.isAfter(end)) {
                throw ApiException.validation("'from' must not be after 'to'",
                        Map.of("from", start.toString(), "to", end.toString()));
            }
            if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
                throw ApiException.validation(
                        "Reporting range must not exceed 5 years",
                        Map.of("from", start.toString(), "to", end.toString()));
            }
            return new Range(start, end);
        }
    }
}
