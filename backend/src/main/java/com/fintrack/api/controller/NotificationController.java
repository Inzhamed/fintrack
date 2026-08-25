package com.fintrack.api.controller;

import com.fintrack.api.dto.notification.NotificationResponse;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.model.Notification;
import com.fintrack.api.repository.NotificationRepository;
import com.fintrack.api.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The in-app notification feed.
 * <p>
 * The durable counterpart to the WebSocket push: a user who was offline when something
 * happened finds it here.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "In-app alerts and reminders")
public class NotificationController {

    /** The feed is a recent history, not an archive. */
    private static final int FEED_LIMIT = 50;

    private final NotificationRepository notificationRepository;

    @GetMapping
    @Operation(summary = "Recent notifications, newest first, with the unread count")
    @Transactional(readOnly = true)
    public NotificationResponse.Feed list(@AuthenticationPrincipal AuthenticatedUser principal) {
        var data = notificationRepository
                .findFeed(principal.id(), PageRequest.of(0, FEED_LIMIT)).stream()
                .map(NotificationResponse::from)
                .toList();

        return new NotificationResponse.Feed(data, notificationRepository.countUnread(principal.id()));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification read")
    @Transactional
    public NotificationResponse markRead(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @PathVariable UUID id) {
        Notification notification = notificationRepository.findOwned(id, principal.id())
                .orElseThrow(() -> ApiException.notFound("Notification", id));
        notification.markRead();
        return NotificationResponse.from(notification);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark every notification read")
    @Transactional
    public void markAllRead(@AuthenticationPrincipal AuthenticatedUser principal) {
        notificationRepository.markAllRead(principal.id(), Instant.now());
    }
}
