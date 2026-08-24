package com.fintrack.api.service;

import com.fintrack.api.dto.budget.*;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.exception.ErrorCode;
import com.fintrack.api.model.*;
import com.fintrack.api.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final BudgetItemRepository budgetItemRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    /**
     * The budget for one month, with each item's progress.
     * <p>
     * Spend is fetched as a single grouped query for the whole month and matched to items
     * in memory. Asking the database per item would be an N+1 that grows with the number of
     * categories the user budgets for - the exact thing that makes a dashboard feel slow.
     */
    @Transactional(readOnly = true)
    public BudgetResponse getByPeriod(UUID userId, int year, int month) {
        validatePeriod(year, month);

        Budget budget = budgetRepository.findByPeriod(userId, year, month)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No budget set for %04d-%02d".formatted(year, month),
                        Map.of("year", year, "month", month)));

        return toResponse(userId, budget);
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> listAll(UUID userId) {
        return budgetRepository.findAllForUser(userId).stream()
                .map(budget -> toResponse(userId, budget))
                .toList();
    }

    @Transactional
    public BudgetResponse create(UUID userId, CreateBudgetRequest request) {
        validatePeriod(request.year(), request.month());

        if (budgetRepository.existsByUserIdAndPeriodYearAndPeriodMonth(
                userId, request.year(), request.month())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "A budget already exists for %04d-%02d. Update its items instead."
                            .formatted(request.year(), request.month()));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));

        Budget budget = Budget.builder()
                .user(user)
                .periodYear(request.year())
                .periodMonth(request.month())
                .currency(request.currency() == null ? user.getBaseCurrency() : request.currency())
                .build();

        if (request.items() != null) {
            Set<UUID> seen = new HashSet<>();
            for (BudgetItemRequest item : request.items()) {
                // Caught here rather than left to the unique constraint, so the message names
                // the offending category instead of surfacing a raw constraint violation.
                if (!seen.add(item.categoryId())) {
                    throw ApiException.validation(
                            "The same category appears more than once in this budget",
                            Map.of("categoryId", item.categoryId().toString()));
                }
                budget.addItem(buildItem(userId, item));
            }
        }

        return toResponse(userId, budgetRepository.save(budget));
    }

    /** Adds one category limit to an existing budget. */
    @Transactional
    public BudgetResponse addItem(UUID userId, UUID budgetId, BudgetItemRequest request) {
        Budget budget = budgetRepository.findOwned(budgetId, userId)
                .orElseThrow(() -> ApiException.notFound("Budget", budgetId));

        if (budgetItemRepository.existsByBudgetIdAndCategoryId(budgetId, request.categoryId())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "This budget already has a limit for that category. Update it instead.");
        }

        budget.addItem(buildItem(userId, request));
        budgetRepository.save(budget);

        return toResponse(userId, budget);
    }

    @Transactional
    public BudgetItemResponse updateItem(UUID userId, UUID itemId,
                                         UpdateBudgetItemRequest request) {
        BudgetItem item = budgetItemRepository.findOwned(itemId, userId)
                .orElseThrow(() -> ApiException.notFound("Budget item", itemId));

        if (request.limitAmount() != null) {
            item.setLimitAmount(request.limitAmount());
        }
        if (request.alertThreshold() != null) {
            item.setAlertThreshold(request.alertThreshold());
        }

        Budget budget = item.getBudget();
        BigDecimal spent = transactionRepository.sumSpentOnCategory(
                userId, item.getCategory().getId(), budget.periodStart(), budget.periodEnd());

        return BudgetItemResponse.of(item, spent);
    }

    @Transactional
    public void deleteItem(UUID userId, UUID itemId) {
        BudgetItem item = budgetItemRepository.findOwned(itemId, userId)
                .orElseThrow(() -> ApiException.notFound("Budget item", itemId));
        // Removed through the aggregate root so orphanRemoval deletes the row and the
        // in-memory collection does not keep a stale reference.
        item.getBudget().removeItem(item);
    }

    @Transactional
    public void delete(UUID userId, UUID budgetId) {
        Budget budget = budgetRepository.findOwned(budgetId, userId)
                .orElseThrow(() -> ApiException.notFound("Budget", budgetId));
        budgetRepository.delete(budget);
    }

    // --- internals ---------------------------------------------------------------------

    private BudgetItem buildItem(UUID userId, BudgetItemRequest request) {
        Category category = categoryRepository.findVisibleToUser(request.categoryId(), userId)
                .orElseThrow(() -> ApiException.notFound("Category", request.categoryId()));

        // Budgets cap spending, so an income category has nothing to limit.
        if (category.getType() != EntryType.EXPENSE) {
            throw ApiException.validation(
                    "Only expense categories can be budgeted; '%s' is an income category"
                            .formatted(category.getName()),
                    Map.of("categoryId", category.getId().toString()));
        }

        return BudgetItem.builder()
                .category(category)
                .limitAmount(request.limitAmount())
                .alertThreshold(request.alertThreshold() == null
                        ? BudgetItem.DEFAULT_ALERT_THRESHOLD
                        : request.alertThreshold())
                .build();
    }

    /**
     * Assembles the response, costing two aggregate queries regardless of item count: one
     * for expense grouped by category, one for the month's income.
     */
    private BudgetResponse toResponse(UUID userId, Budget budget) {
        LocalDate start = budget.periodStart();
        LocalDate end = budget.periodEnd();

        List<CategoryTotal> expenseTotals =
                transactionRepository.totalsByCategory(userId, EntryType.EXPENSE, start, end);

        // A null categoryId groups the uncategorised rows, so it cannot be a map key.
        Map<UUID, BigDecimal> spendByCategory = new HashMap<>();
        BigDecimal uncategorisedSpend = BigDecimal.ZERO;
        for (CategoryTotal total : expenseTotals) {
            if (total.categoryId() == null) {
                uncategorisedSpend = uncategorisedSpend.add(total.total());
            } else {
                spendByCategory.put(total.categoryId(), total.total());
            }
        }

        List<BudgetItemResponse> items = budget.getItems().stream()
                .map(item -> BudgetItemResponse.of(
                        item, spendByCategory.get(item.getCategory().getId())))
                .sorted(Comparator.comparing(item -> item.category().name()))
                .toList();

        BigDecimal totalLimit = items.stream()
                .map(BudgetItemResponse::limitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSpent = items.stream()
                .map(BudgetItemResponse::spent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Expense in categories this budget says nothing about. Without this, a user could
        // be "on budget" on every tracked line and still overspend for the month.
        Set<UUID> budgetedCategories = budget.getItems().stream()
                .map(item -> item.getCategory().getId())
                .collect(java.util.stream.Collectors.toSet());
        BigDecimal unbudgetedSpend = spendByCategory.entrySet().stream()
                .filter(entry -> !budgetedCategories.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalIncome =
                transactionRepository.sumByType(userId, EntryType.INCOME, start, end);

        BigDecimal percentUsed = totalLimit.signum() == 0
                ? BigDecimal.ZERO
                : totalSpent.multiply(BigDecimal.valueOf(100))
                        .divide(totalLimit, 1, RoundingMode.HALF_UP);

        int warning = (int) items.stream()
                .filter(item -> item.status() == BudgetStatus.WARNING).count();
        int exceeded = (int) items.stream()
                .filter(item -> item.status() == BudgetStatus.EXCEEDED).count();

        return new BudgetResponse(
                budget.getId(),
                budget.getPeriodYear(),
                budget.getPeriodMonth(),
                budget.getCurrency(),
                start,
                end,
                items,
                new BudgetResponse.Totals(
                        totalLimit, totalSpent, totalLimit.subtract(totalSpent), percentUsed,
                        uncategorisedSpend, unbudgetedSpend, totalIncome, warning, exceeded));
    }

    private static void validatePeriod(int year, int month) {
        if (month < 1 || month > 12) {
            throw ApiException.validation("Month must be between 1 and 12",
                    Map.of("month", month));
        }
        if (year < 2000 || year > 2100) {
            throw ApiException.validation("Year must be between 2000 and 2100",
                    Map.of("year", year));
        }
        // YearMonth.of would throw for an out-of-range value; both checks above already
        // guarantee it succeeds, so this simply documents that the period is well-formed.
        YearMonth.of(year, month);
    }
}
