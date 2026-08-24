package com.fintrack.api.repository;

import com.fintrack.api.model.EntryType;
import com.fintrack.api.model.Transaction;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Composable predicates for the transaction list endpoint.
 * <p>
 * The alternative - a finder method per filter combination - grows combinatorially, and a
 * single JPQL query with {@code (:param IS NULL OR ...)} for each optional filter defeats
 * the query planner, since the generated SQL is identical whether or not a filter is set.
 * Specifications build only the predicates actually requested.
 * <p>
 * Every query starts from {@link #ownedBy}, which is what keeps one user's rows invisible
 * to another.
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {}

    /** Mandatory. Scopes the query to a single user's rows. */
    public static Specification<Transaction> ownedBy(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    /**
     * Fetches the category alongside each row so rendering N transactions does not fire N
     * extra selects. Skipped on the count query, where a fetch join is both useless and
     * illegal.
     */
    public static Specification<Transaction> withCategory() {
        return (root, query, cb) -> {
            if (query != null && Long.class != query.getResultType()
                    && long.class != query.getResultType()) {
                root.fetch("category", JoinType.LEFT);
                query.distinct(true);
            }
            return cb.conjunction();
        };
    }

    public static Specification<Transaction> ofType(EntryType type) {
        return type == null ? null : (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Transaction> inCategory(UUID categoryId) {
        return categoryId == null ? null
                : (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    /** Transactions with no category at all. */
    public static Specification<Transaction> uncategorised() {
        return (root, query, cb) -> cb.isNull(root.get("category"));
    }

    public static Specification<Transaction> occurredOnOrAfter(LocalDate from) {
        return from == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredOn"), from);
    }

    public static Specification<Transaction> occurredOnOrBefore(LocalDate to) {
        return to == null ? null
                : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredOn"), to);
    }

    public static Specification<Transaction> amountAtLeast(BigDecimal min) {
        return min == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("amount"), min);
    }

    public static Specification<Transaction> amountAtMost(BigDecimal max) {
        return max == null ? null
                : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("amount"), max);
    }

    /**
     * Case-insensitive substring match across description and merchant.
     * <p>
     * A leading wildcard cannot use a b-tree index, so this is a sequential scan within the
     * user's rows. Fine at personal-finance scale; if it ever isn't, the fix is a trigram
     * index or a tsvector column, not a different Specification.
     */
    public static Specification<Transaction> matching(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("description")), pattern),
                cb.like(cb.lower(root.get("merchant")), pattern));
    }
}
