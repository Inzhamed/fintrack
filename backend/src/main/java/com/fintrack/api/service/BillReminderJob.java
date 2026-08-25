package com.fintrack.api.service;

import com.fintrack.api.model.Bill;
import com.fintrack.api.model.NotificationType;
import com.fintrack.api.repository.BillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Sends a reminder before each bill falls due.
 *
 * <h2>Idempotency</h2>
 * Each bill records the due date it was last reminded for. A reminder is only sent when the
 * upcoming occurrence differs from that value, so running the job twice in a day - or
 * catching up after the service was down - still produces exactly one reminder per
 * occurrence. Without it, a restart loop would notify the user once per restart.
 *
 * <h2>Running on more than one instance</h2>
 * {@code @Scheduled} fires on every instance, so two replicas would both scan and both
 * notify. A short Redis lock means only one wins the tick; the other finds the key taken and
 * returns. The lock has a TTL rather than being released only on success, so an instance
 * that dies mid-run does not block the job forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillReminderJob {

    private static final String LOCK_KEY = "scheduler:bill-reminders";

    /**
     * Comfortably longer than a normal run, short enough that a crashed instance frees the
     * lock before the next scheduled tick.
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final BillRepository billRepository;
    private final NotificationService notificationService;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;

    /**
     * Runs hourly rather than daily.
     * <p>
     * A once-a-day job that happens to be down at its appointed minute skips the day
     * entirely. Hourly plus the idempotency check means the reminder still goes out, at worst
     * an hour late, and never twice.
     */
    @Scheduled(cron = "0 5 * * * *")
    public void sendDueReminders() {
        if (!acquireLock()) {
            log.debug("Another instance is running the bill reminder scan");
            return;
        }
        run(LocalDate.now());
    }

    /**
     * The scan itself, with the date injected so tests can drive it without waiting for a
     * cron tick or manipulating the system clock.
     *
     * @return how many reminders were sent
     */
    @Transactional
    public int run(LocalDate today) {
        int sent = 0;

        for (Bill bill : billRepository.findAllActive()) {
            LocalDate dueOn = bill.nextDueOnOrAfter(today);
            long daysAway = ChronoUnit.DAYS.between(today, dueOn);

            if (daysAway > bill.getRemindDaysBefore()) {
                continue;   // Not yet inside the reminder window.
            }
            if (dueOn.equals(bill.getLastRemindedFor())) {
                continue;   // Already reminded for this occurrence.
            }

            notifyDue(bill, dueOn, daysAway);
            // Recorded before the transaction commits, so the reminder and the record of it
            // land together. A push that fails is swallowed inside NotificationService, which
            // is deliberate: a delivery failure must not cause the same reminder to be sent
            // again on the next tick.
            bill.setLastRemindedFor(dueOn);
            sent++;
        }

        if (sent > 0) {
            log.info("Sent {} bill reminder(s)", sent);
        }
        return sent;
    }

    private void notifyDue(Bill bill, LocalDate dueOn, long daysAway) {
        String when = switch ((int) daysAway) {
            case 0 -> "today";
            case 1 -> "tomorrow";
            default -> "in %d days".formatted(daysAway);
        };

        Map<String, Object> payload = new HashMap<>();
        payload.put("billId", bill.getId().toString());
        payload.put("dueOn", dueOn.toString());
        payload.put("amount", bill.getAmount().toPlainString());
        payload.put("currency", bill.getCurrency());
        if (bill.getCategory() != null) {
            payload.put("categoryId", bill.getCategory().getId().toString());
            payload.put("categoryName", bill.getCategory().getName());
        }

        notificationService.publish(
                bill.getUser().getId(),
                NotificationType.BILL_DUE,
                "%s is due %s".formatted(bill.getName(), when),
                "%s %s for %s is due on %s."
                        .formatted(bill.getAmount().toPlainString(), bill.getCurrency(),
                                bill.getName(), dueOn),
                payload);
    }

    /**
     * @return true when this instance may run the scan. Falls open without Redis, since a
     *         single-instance deployment has nothing to coordinate with.
     */
    private boolean acquireLock() {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return true;
        }
        try {
            Boolean acquired = template.opsForValue()
                    .setIfAbsent(LOCK_KEY, "held", LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ex) {
            // A missing lock should not stop reminders going out; at worst a second instance
            // also runs, and the idempotency check still prevents a duplicate notification.
            log.warn("Could not acquire the scheduler lock; running anyway", ex);
            return true;
        }
    }
}
