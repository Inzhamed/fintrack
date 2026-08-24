package com.fintrack.api.repository;

import com.fintrack.api.model.BudgetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, UUID> {

    /** Ownership is reached through the parent budget, which is the aggregate root. */
    @Query("""
            SELECT i FROM BudgetItem i
            JOIN FETCH i.budget b
            JOIN FETCH i.category
            WHERE i.id = :id AND b.user.id = :userId
            """)
    Optional<BudgetItem> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    boolean existsByBudgetIdAndCategoryId(UUID budgetId, UUID categoryId);
}
