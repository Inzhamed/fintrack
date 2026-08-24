package com.fintrack.api.repository;

import com.fintrack.api.model.EntryType;
import com.fintrack.api.model.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Extends {@link JpaSpecificationExecutor} so the list endpoint can compose its optional
 * filters (date range, type, category, search, amount bounds) into one query instead of
 * multiplying finder methods. See {@code TransactionSpecifications}.
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    /** Ownership-checked read. Never load a transaction by id alone. */
    @Query("SELECT t FROM Transaction t LEFT JOIN FETCH t.category WHERE t.id = :id AND t.user.id = :userId")
    Optional<Transaction> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Total for one direction over a closed date range. Coalesced so an empty range
     * returns zero rather than null.
     */
    @Query("""
            SELECT coalesce(sum(t.amount), 0) FROM Transaction t
            WHERE t.user.id = :userId AND t.type = :type
              AND t.occurredOn BETWEEN :from AND :to
            """)
    BigDecimal sumByType(@Param("userId") UUID userId,
                         @Param("type") EntryType type,
                         @Param("from") LocalDate from,
                         @Param("to") LocalDate to);

    /**
     * Spend per category over a range, largest first. Uncategorised transactions group
     * under a null id, which the service layer renders as "Uncategorised".
     */
    @Query("""
            SELECT new com.fintrack.api.repository.CategoryTotal(
                       c.id, c.name, c.color, t.type, sum(t.amount), count(t))
            FROM Transaction t LEFT JOIN t.category c
            WHERE t.user.id = :userId AND t.type = :type
              AND t.occurredOn BETWEEN :from AND :to
            GROUP BY c.id, c.name, c.color, t.type
            ORDER BY sum(t.amount) DESC
            """)
    List<CategoryTotal> totalsByCategory(@Param("userId") UUID userId,
                                         @Param("type") EntryType type,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    /** Spend against one category in a period — the number a budget item is measured against. */
    @Query("""
            SELECT coalesce(sum(t.amount), 0) FROM Transaction t
            WHERE t.user.id = :userId AND t.category.id = :categoryId
              AND t.type = com.fintrack.api.model.EntryType.EXPENSE
              AND t.occurredOn BETWEEN :from AND :to
            """)
    BigDecimal sumSpentOnCategory(@Param("userId") UUID userId,
                                  @Param("categoryId") UUID categoryId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /**
     * Sum and count per direction in one round trip, for the dashboard summary. Returns at
     * most two rows, and none at all for a period with no activity.
     */
    @Query("""
            SELECT new com.fintrack.api.repository.TypeTotal(t.type, sum(t.amount), count(t))
            FROM Transaction t
            WHERE t.user.id = :userId AND t.occurredOn BETWEEN :from AND :to
            GROUP BY t.type
            """)
    List<TypeTotal> totalsByType(@Param("userId") UUID userId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    /**
     * Monthly income and expense totals across a range, for the cashflow chart.
     * <p>
     * Months with no activity simply do not appear; the service fills those gaps, since a
     * chart that skips empty months draws a misleading slope between the ones it has.
     */
    @Query("""
            SELECT new com.fintrack.api.repository.PeriodTotal(
                       year(t.occurredOn), month(t.occurredOn), t.type, sum(t.amount), count(t))
            FROM Transaction t
            WHERE t.user.id = :userId AND t.occurredOn BETWEEN :from AND :to
            GROUP BY year(t.occurredOn), month(t.occurredOn), t.type
            ORDER BY year(t.occurredOn), month(t.occurredOn)
            """)
    List<PeriodTotal> totalsByMonth(@Param("userId") UUID userId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /** Most recent activity for the dashboard, category fetched to avoid an N+1. */
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.category
            WHERE t.user.id = :userId
            ORDER BY t.occurredOn DESC, t.createdAt DESC
            """)
    List<Transaction> findRecent(@Param("userId") UUID userId, Pageable pageable);

    boolean existsByCategoryId(UUID categoryId);
}
