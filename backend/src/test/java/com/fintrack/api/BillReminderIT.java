package com.fintrack.api;

import com.fintrack.api.model.Bill;
import com.fintrack.api.model.Recurrence;
import com.fintrack.api.repository.BillRepository;
import com.fintrack.api.repository.NotificationRepository;
import com.fintrack.api.service.BillReminderJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BillReminderIT extends AbstractIntegrationTest {

    @Autowired private BillReminderJob reminderJob;
    @Autowired private BillRepository billRepository;
    @Autowired private NotificationRepository notificationRepository;

    private JsonNode createBill(String token, String body) throws Exception {
        return json(mockMvc.perform(authed(post("/api/v1/bills"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
    }

    // --- the recurrence rule --------------------------------------------------------------

    @Test
    @DisplayName("a due day past the end of a short month is clamped, not skipped")
    void clampsDueDayToMonthLength() {
        Bill bill = Bill.builder()
                .recurrence(Recurrence.MONTHLY)
                .dueDay(31)
                .build();

        // February has no 31st. Clamping to the last day is the only reading that keeps the
        // bill due once a month; rolling into March would skip February entirely.
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 2, 1)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2024, 2, 1)))
                .as("leap year")
                .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 4, 1)))
                .isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 1, 1)))
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("once this month's date has passed, the next occurrence is next month")
    void rollsForwardAfterTheDueDate() {
        Bill bill = Bill.builder().recurrence(Recurrence.MONTHLY).dueDay(5).build();

        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 8, 1)))
                .isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 8, 5)))
                .as("on the day itself, it is still due today")
                .isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 8, 6)))
                .isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 12, 20)))
                .as("rolls across the year boundary")
                .isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    @DisplayName("a yearly bill rolls to the following year")
    void handlesYearlyBills() {
        Bill bill = Bill.builder()
                .recurrence(Recurrence.YEARLY)
                .dueDay(15)
                .dueMonth(3)
                .build();

        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 1, 1)))
                .isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(bill.nextDueOnOrAfter(LocalDate.of(2026, 6, 1)))
                .isEqualTo(LocalDate.of(2027, 3, 15));
    }

    // --- the scheduler ---------------------------------------------------------------------

    @Test
    @DisplayName("a bill inside its reminder window produces exactly one notification")
    void remindsOnceInsideTheWindow() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        LocalDate today = LocalDate.of(2026, 8, 10);

        createBill(token, """
                {"name":"Internet","amount":3500,"recurrence":"MONTHLY","dueDay":12,
                 "remindDaysBefore":3}""");

        // Due in 2 days, inside the 3-day window.
        assertThat(reminderJob.run(today)).isEqualTo(1);

        mockMvc.perform(authed(get("/api/v1/notifications"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.data[0].type").value("BILL_DUE"))
                .andExpect(jsonPath("$.data[0].title").value("Internet is due in 2 days"))
                .andExpect(jsonPath("$.data[0].payload.dueOn").value("2026-08-12"));
    }

    @Test
    @DisplayName("running the scan again sends nothing more for the same occurrence")
    void isIdempotentAcrossRuns() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        LocalDate today = LocalDate.of(2026, 8, 10);

        createBill(token, """
                {"name":"Internet","amount":3500,"recurrence":"MONTHLY","dueDay":12}""");

        assertThat(reminderJob.run(today)).isEqualTo(1);
        // The job runs hourly, and may re-run after a restart. Without the idempotency check
        // the user would be reminded once per tick for three days.
        assertThat(reminderJob.run(today)).isZero();
        assertThat(reminderJob.run(today.plusDays(1))).isZero();

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the next month's occurrence is reminded about again")
    void remindsAgainNextCycle() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        createBill(token, """
                {"name":"Rent","amount":30000,"recurrence":"MONTHLY","dueDay":1,
                 "remindDaysBefore":2}""");

        assertThat(reminderJob.run(LocalDate.of(2026, 8, 30))).isEqualTo(1);   // 1 Sept
        assertThat(reminderJob.run(LocalDate.of(2026, 9, 1))).isZero();        // same occurrence
        assertThat(reminderJob.run(LocalDate.of(2026, 9, 29))).isEqualTo(1);   // 1 Oct

        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a bill outside its window is left alone")
    void staysQuietOutsideTheWindow() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createBill(token, """
                {"name":"Internet","amount":3500,"recurrence":"MONTHLY","dueDay":28,
                 "remindDaysBefore":3}""");

        assertThat(reminderJob.run(LocalDate.of(2026, 8, 10))).isZero();
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("an inactive bill is never reminded about")
    void ignoresInactiveBills() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        String billId = createBill(token, """
                {"name":"Gym","amount":4000,"recurrence":"MONTHLY","dueDay":12}""")
                .get("id").asString();

        mockMvc.perform(authed(patch("/api/v1/bills/" + billId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(reminderJob.run(LocalDate.of(2026, 8, 10))).isZero();
    }

    @Test
    @DisplayName("moving the due date makes the bill eligible to remind again")
    void reschedulingClearsTheReminderRecord() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        LocalDate today = LocalDate.of(2026, 8, 10);

        String billId = createBill(token, """
                {"name":"Internet","amount":3500,"recurrence":"MONTHLY","dueDay":12}""")
                .get("id").asString();

        assertThat(reminderJob.run(today)).isEqualTo(1);
        assertThat(reminderJob.run(today)).isZero();

        // The next occurrence is now a different date, so the previous record no longer
        // describes it and the user should hear about the new one.
        mockMvc.perform(authed(patch("/api/v1/bills/" + billId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dueDay":11}"""))
                .andExpect(status().isOk());

        assertThat(reminderJob.run(today)).isEqualTo(1);
    }

    @Test
    @DisplayName("each user is reminded only about their own bills")
    void isolatesUsers() throws Exception {
        String hamed = registerAndLogin("hamed@example.com");
        String other = registerAndLogin("someone-else@example.com");

        createBill(hamed, """
                {"name":"Hamed's rent","amount":30000,"recurrence":"MONTHLY","dueDay":12}""");
        createBill(other, """
                {"name":"Their rent","amount":40000,"recurrence":"MONTHLY","dueDay":12}""");

        assertThat(reminderJob.run(LocalDate.of(2026, 8, 10))).isEqualTo(2);

        mockMvc.perform(authed(get("/api/v1/notifications"), hamed))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Hamed's rent is due in 2 days"));

        mockMvc.perform(authed(get("/api/v1/notifications"), other))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Their rent is due in 2 days"));
    }

    // --- the feed ---------------------------------------------------------------------------

    @Test
    @DisplayName("notifications can be marked read, individually and in bulk")
    void marksNotificationsRead() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createBill(token, """
                {"name":"Internet","amount":3500,"recurrence":"MONTHLY","dueDay":12}""");
        createBill(token, """
                {"name":"Gym","amount":4000,"recurrence":"MONTHLY","dueDay":11}""");
        reminderJob.run(LocalDate.of(2026, 8, 10));

        JsonNode feed = json(mockMvc.perform(authed(get("/api/v1/notifications"), token))
                .andExpect(jsonPath("$.unread").value(2))
                .andReturn());

        String first = feed.get("data").get(0).get("id").asString();
        mockMvc.perform(authed(post("/api/v1/notifications/" + first + "/read"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(authed(get("/api/v1/notifications"), token))
                .andExpect(jsonPath("$.unread").value(1));

        mockMvc.perform(authed(post("/api/v1/notifications/read-all"), token))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(get("/api/v1/notifications"), token))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("one user cannot read or mark another user's notification")
    void feedIsPrivate() throws Exception {
        String hamed = registerAndLogin("hamed@example.com");
        createBill(hamed, """
                {"name":"Rent","amount":30000,"recurrence":"MONTHLY","dueDay":12}""");
        reminderJob.run(LocalDate.of(2026, 8, 10));

        String id = json(mockMvc.perform(authed(get("/api/v1/notifications"), hamed)).andReturn())
                .get("data").get(0).get("id").asString();

        String other = registerAndLogin("someone-else@example.com");
        mockMvc.perform(authed(get("/api/v1/notifications"), other))
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(authed(post("/api/v1/notifications/" + id + "/read"), other))
                .andExpect(status().isNotFound());
    }

    // --- bill validation ---------------------------------------------------------------------

    @Test
    @DisplayName("a yearly bill needs a due month and a monthly one must not have it")
    void enforcesRecurrenceConsistency() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(post("/api/v1/bills"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Insurance","amount":50000,"recurrence":"YEARLY","dueDay":15}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(authed(post("/api/v1/bills"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Internet","amount":3500,"recurrence":"MONTHLY","dueDay":12,"dueMonth":6}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("bills are scoped to their owner")
    void billsArePrivate() throws Exception {
        String hamed = registerAndLogin("hamed@example.com");
        String billId = createBill(hamed, """
                {"name":"Rent","amount":30000,"recurrence":"MONTHLY","dueDay":1}""")
                .get("id").asString();

        String other = registerAndLogin("someone-else@example.com");
        mockMvc.perform(authed(get("/api/v1/bills"), other))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(authed(delete("/api/v1/bills/" + billId), other))
                .andExpect(status().isNotFound());

        assertThat(billRepository.count()).isEqualTo(1);
    }
}
