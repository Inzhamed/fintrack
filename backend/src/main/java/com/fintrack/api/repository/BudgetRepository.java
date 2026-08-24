package com.fintrack.api.repository;

import com.fintrack.api.model.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    /**
     * Loads the budget with its items and their categories in one query. Without the fetch
     * joins, rendering a budget of N items costs N+1 round trips.
     */
    @Query("""
            SELECT b FROM Budget b
            LEFT JOIN FETCH b.items i
            LEFT JOIN FETCH i.category
            WHERE b.user.id = :userId AND b.periodYear = :year AND b.periodMonth = :month
            """)
    Optional<Budget> findByPeriod(@Param("userId") UUID userId,
                                  @Param("year") int year,
                                  @Param("month") int month);

    @Query("SELECT b FROM Budget b WHERE b.id = :id AND b.user.id = :userId")
    Optional<Budget> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT b FROM Budget b WHERE b.user.id = :userId ORDER BY b.periodYear DESC, b.periodMonth DESC")
    List<Budget> findAllForUser(@Param("userId") UUID userId);

    boolean existsByUserIdAndPeriodYearAndPeriodMonth(UUID userId, int periodYear, int periodMonth);
}
