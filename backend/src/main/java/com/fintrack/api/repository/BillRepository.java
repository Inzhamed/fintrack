package com.fintrack.api.repository;

import com.fintrack.api.model.Bill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillRepository extends JpaRepository<Bill, UUID> {

    @Query("""
            SELECT b FROM Bill b
            LEFT JOIN FETCH b.category
            WHERE b.user.id = :userId
            ORDER BY b.active DESC, b.dueDay
            """)
    List<Bill> findAllForUser(@Param("userId") UUID userId);

    @Query("""
            SELECT b FROM Bill b
            LEFT JOIN FETCH b.category
            WHERE b.id = :id AND b.user.id = :userId
            """)
    Optional<Bill> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Every active bill, for the reminder scan.
     * <p>
     * Crosses user boundaries by design - this is the one query in the application that does,
     * and it is called only by the scheduler, never in response to a request. The user and
     * category are fetched because the reminder names both.
     */
    @Query("""
            SELECT b FROM Bill b
            JOIN FETCH b.user
            LEFT JOIN FETCH b.category
            WHERE b.active = true
            """)
    List<Bill> findAllActive();

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);
}
