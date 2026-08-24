package com.fintrack.api.repository;

import com.fintrack.api.model.Category;
import com.fintrack.api.model.EntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /**
     * Everything the user may file a transaction under: the global defaults plus their own.
     * Globals sort first, then alphabetically.
     */
    @Query("""
            SELECT c FROM Category c
            WHERE (c.owner IS NULL OR c.owner.id = :userId)
              AND (:type IS NULL OR c.type = :type)
            ORDER BY CASE WHEN c.owner IS NULL THEN 0 ELSE 1 END, c.name
            """)
    List<Category> findVisibleTo(@Param("userId") UUID userId, @Param("type") EntryType type);

    /** Resolves a category the user is allowed to use — their own, or a global default. */
    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.owner IS NULL OR c.owner.id = :userId)")
    Optional<Category> findVisibleToUser(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Only a category the user owns may be edited or deleted; globals are read-only. */
    @Query("SELECT c FROM Category c WHERE c.id = :id AND c.owner.id = :userId")
    Optional<Category> findOwnedBy(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("""
            SELECT count(c) > 0 FROM Category c
            WHERE c.owner.id = :userId AND lower(c.name) = lower(:name) AND c.type = :type
            """)
    boolean existsForUserByNameAndType(@Param("userId") UUID userId,
                                       @Param("name") String name,
                                       @Param("type") EntryType type);
}
