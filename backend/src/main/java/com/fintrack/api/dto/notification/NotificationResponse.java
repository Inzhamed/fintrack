package com.fintrack.api.dto.notification;

import com.fintrack.api.model.Notification;
import com.fintrack.api.model.NotificationType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String body,
        Map<String, Object> payload,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getPayload(),
                notification.isRead(),
                notification.getCreatedAt());
    }

    /** The feed plus the badge count, so a client needs one request rather than two. */
    public record Feed(java.util.List<NotificationResponse> data, long unread) {}
}
