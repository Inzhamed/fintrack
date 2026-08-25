package com.fintrack.api.service;

import com.fintrack.api.dto.budget.BudgetStatus;
import com.fintrack.api.dto.notification.NotificationMessage;
import com.fintrack.api.model.BudgetItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Pushes messages to a single user's WebSocket session. */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    /** The per-user queue clients subscribe to as {@code /user/queue/notifications}. */
    public static final String DESTINATION = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Sends to one user, addressed by id.
     * <p>
     * The id, not the email: it is what the STOMP interceptor puts in the principal's name,
     * and unlike an email it never changes. Delivery is best-effort - a user with no open
     * session simply does not receive it, which is why alerts are also recomputed and
     * returned by the dashboard rather than existing only as a push.
     */
    public void send(UUID userId, NotificationMessage message) {
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), DESTINATION, message);
        } catch (RuntimeException ex) {
            // A failed push must never fail the write that triggered it. The user would
            // rather have their transaction saved without an alert than lose both.
            log.warn("Could not deliver notification to user {}", userId, ex);
        }
    }

    /**
     * Alerts on a budget item, but only when this transaction is what crossed the line.
     *
     * @param item        the budget line being measured
     * @param spentBefore spend in the period before the transaction that triggered this
     * @param spentAfter  spend including it
     * @return true when a message was sent
     */
    public boolean notifyIfThresholdCrossed(UUID userId, BudgetItem item, String currency,
                                            BigDecimal spentBefore, BigDecimal spentAfter) {

        BudgetStatus before = statusOf(spentBefore, item);
        BudgetStatus after = statusOf(spentAfter, item);

        // Only on a transition. Alerting whenever spend is *over* the line would mean every
        // subsequent purchase in an already-exceeded category fires another notification -
        // the user is told once and then nagged for the rest of the month.
        if (after == before || after == BudgetStatus.ON_TRACK) {
            return false;
        }

        BigDecimal percent = item.getLimitAmount().signum() == 0
                ? BigDecimal.ZERO
                : spentAfter.multiply(BigDecimal.valueOf(100))
                        .divide(item.getLimitAmount(), 1, RoundingMode.HALF_UP);

        var category = item.getCategory();
        var alert = new NotificationMessage.BudgetAlert(
                item.getId(),
                category.getId(),
                category.getName(),
                category.getColor(),
                spentAfter,
                item.getLimitAmount(),
                percent,
                after);

        send(userId, NotificationMessage.budgetThreshold(alert, currency));
        log.info("Budget {} alert for user {} on category {}", after, userId, category.getName());
        return true;
    }

    private static BudgetStatus statusOf(BigDecimal spent, BudgetItem item) {
        if (spent.compareTo(item.getLimitAmount()) > 0) {
            return BudgetStatus.EXCEEDED;
        }
        if (spent.compareTo(item.alertAmount()) >= 0) {
            return BudgetStatus.WARNING;
        }
        return BudgetStatus.ON_TRACK;
    }
}
