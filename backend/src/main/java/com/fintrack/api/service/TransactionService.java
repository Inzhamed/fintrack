package com.fintrack.api.service;

import com.fintrack.api.dto.common.PageResponse;
import com.fintrack.api.dto.transaction.CreateTransactionRequest;
import com.fintrack.api.dto.transaction.TransactionFilter;
import com.fintrack.api.dto.transaction.TransactionResponse;
import com.fintrack.api.dto.transaction.UpdateTransactionRequest;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.model.Category;
import com.fintrack.api.model.Transaction;
import com.fintrack.api.model.User;
import com.fintrack.api.repository.TransactionRepository;
import com.fintrack.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.fintrack.api.repository.TransactionSpecifications.*;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryService categoryService;
    private final UserRepository userRepository;

    /**
     * Filtered, paginated list, newest first by default.
     * <p>
     * {@code ownedBy} is applied first and unconditionally, so no combination of query
     * parameters can widen the result beyond the caller's own rows.
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(UUID userId, TransactionFilter filter,
                                                  Pageable pageable) {
        if (filter.categoryId() != null && filter.wantsUncategorised()) {
            throw ApiException.validation(
                    "categoryId and uncategorised cannot be combined",
                    Map.of("categoryId", filter.categoryId().toString(), "uncategorised", true));
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw ApiException.validation("'from' must not be after 'to'",
                    Map.of("from", filter.from().toString(), "to", filter.to().toString()));
        }

        // Specification.and() rejects null as of Spring Data JPA 4, so the inapplicable
        // filters are dropped before combining rather than chained through. allOf also
        // keeps the generated SQL free of no-op 1=1 conjunctions.
        List<Specification<Transaction>> predicates = new ArrayList<>();
        predicates.add(ownedBy(userId));
        predicates.add(withCategory());
        predicates.add(ofType(filter.type()));
        predicates.add(occurredOnOrAfter(filter.from()));
        predicates.add(occurredOnOrBefore(filter.to()));
        predicates.add(amountAtLeast(filter.minAmount()));
        predicates.add(amountAtMost(filter.maxAmount()));
        predicates.add(matching(filter.search()));
        predicates.add(filter.wantsUncategorised()
                ? uncategorised()
                : inCategory(filter.categoryId()));
        predicates.removeIf(Objects::isNull);

        Page<Transaction> page =
                transactionRepository.findAll(Specification.allOf(predicates), pageable);
        return PageResponse.from(page, TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID userId, UUID transactionId) {
        return TransactionResponse.from(ownedOrFail(userId, transactionId));
    }

    @Transactional
    public TransactionResponse create(UUID userId, CreateTransactionRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));

        Category category = request.categoryId() == null ? null
                : categoryService.resolveUsable(userId, request.categoryId(), request.type());

        Transaction transaction = Transaction.builder()
                .user(user)
                .category(category)
                .type(request.type())
                .amount(request.amount())
                // Falling back to the account's base currency keeps single-currency users -
                // which is nearly all of them - from having to state it on every entry.
                .currency(request.currency() == null ? user.getBaseCurrency() : request.currency())
                .description(trimToNull(request.description()))
                .merchant(trimToNull(request.merchant()))
                .occurredOn(request.occurredOn() == null ? LocalDate.now() : request.occurredOn())
                .build();

        // saveAndFlush so @CreationTimestamp/@UpdateTimestamp are populated before the
        // response is built; plain save() defers the insert and returns null timestamps.
        return TransactionResponse.from(transactionRepository.saveAndFlush(transaction));
    }

    /** Partial update. A null field means "leave unchanged". */
    @Transactional
    public TransactionResponse update(UUID userId, UUID transactionId,
                                      UpdateTransactionRequest request) {
        Transaction transaction = ownedOrFail(userId, transactionId);

        if (request.wantsCategoryCleared() && request.categoryId() != null) {
            throw ApiException.validation(
                    "clearCategory and categoryId cannot both be set",
                    Map.of("categoryId", request.categoryId().toString()));
        }

        if (request.wantsCategoryCleared()) {
            transaction.setCategory(null);
        } else if (request.categoryId() != null) {
            // Re-checked against the transaction's own type, so a PATCH cannot sneak an
            // income category onto an expense.
            transaction.setCategory(
                    categoryService.resolveUsable(userId, request.categoryId(), transaction.getType()));
        }

        if (request.amount() != null) {
            transaction.setAmount(request.amount());
        }
        if (request.currency() != null) {
            transaction.setCurrency(request.currency());
        }
        if (request.description() != null) {
            transaction.setDescription(trimToNull(request.description()));
        }
        if (request.merchant() != null) {
            transaction.setMerchant(trimToNull(request.merchant()));
        }
        if (request.occurredOn() != null) {
            transaction.setOccurredOn(request.occurredOn());
        }

        return TransactionResponse.from(transaction);
    }

    @Transactional
    public void delete(UUID userId, UUID transactionId) {
        transactionRepository.delete(ownedOrFail(userId, transactionId));
    }

    private Transaction ownedOrFail(UUID userId, UUID transactionId) {
        return transactionRepository.findOwned(transactionId, userId)
                .orElseThrow(() -> ApiException.notFound("Transaction", transactionId));
    }

    /** An empty string in a request body means "not provided", not "the empty value". */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
