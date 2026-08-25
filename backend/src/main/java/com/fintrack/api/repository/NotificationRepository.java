package com.fintrack.api.repository;

import com.fintrack.api.model.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("SELECT n FROM Notification n WHERE n.user.id = :userId ORDER BY n.createdAt DESC")
    List<Notification> findFeed(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT count(n) FROM Notification n WHERE n.user.id = :userId AND n.readAt IS NULL")
    long countUnread(@Param("userId") UUID userId);

    @Query("SELECT n FROM Notification n WHERE n.id = :id AND n.user.id = :userId")
    Optional<Notification> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Bulk "mark all read", as one UPDATE rather than a read-modify-write per row. */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user.id = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Housekeeping: the feed is not an archive. */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
