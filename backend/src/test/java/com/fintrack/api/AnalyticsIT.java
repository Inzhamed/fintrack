package com.fintrack.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyticsIT extends AbstractIntegrationTest {

    /**
     * Dates are relative to the current month, because the dashboard reports on "now" and a
     * suite pinned to fixed dates would start failing the moment the calendar moved past it.
     */
    private static final YearMonth THIS_MONTH = YearMonth.now(FixedClockConfiguration.CLOCK);

    private static String day(int dayOfMonth) {
        return THIS_MONTH.atDay(dayOfMonth).toString();
    }

    private static String dayLastMonth(int dayOfMonth) {
        return THIS_MONTH.minusMonths(1).atDay(dayOfMonth).toString();
    }

    /** 85000 income, 39400 expense this month; 85000 / 62000 last month. */
    private String seed() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID salary = globalCategoryId(token, "Salary");
        UUID rent = globalCategoryId(token, "Rent");
        UUID groceries = globalCategoryId(token, "Groceries");
        UUID transport = globalCategoryId(token, "Transport");

        createTransaction(token, """
                {"type":"INCOME","amount":85000,"categoryId":"%s","occurredOn":"%s"}
                """.formatted(salary, day(1)));
        createTransaction(token, """
                {"type":"EXPENSE","amount":30000,"categoryId":"%s","occurredOn":"%s"}
                """.formatted(rent, day(2)));
        createTransaction(token, """
                {"type":"EXPENSE","amount":7450,"categoryId":"%s","occurredOn":"%s"}
                """.formatted(groceries, day(5)));
        createTransaction(token, """
                {"type":"EXPENSE","amount":1500,"categoryId":"%s","occurredOn":"%s"}
                """.formatted(transport, day(8)));
        createTransaction(token, """
                {"type":"EXPENSE","amount":450,"occurredOn":"%s"}""".formatted(day(18)));

        createTransaction(token, """
                {"type":"INCOME","amount":85000,"categoryId":"%s","occurredOn":"%s"}
                """.formatted(salary, dayLastMonth(1)));
        createTransaction(token, """
                {"type":"EXPENSE","amount":62000,"categoryId":"%s","occurredOn":"%s"}
                """.formatted(rent, dayLastMonth(3)));

        return token;
    }

    @Test
    @DisplayName("summary reports income, expense, balance and savings rate for the period")
    void summarises() throws Exception {
        String token = seed();

        mockMvc.perform(authed(get("/api/v1/analytics/summary"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(85000))
                .andExpect(jsonPath("$.totalExpense").value(39400))
                .andExpect(jsonPath("$.balance").value(45600))
                // (85000 - 39400) / 85000
                .andExpect(jsonPath("$.savingsRate").value(53.6))
                .andExpect(jsonPath("$.incomeCount").value(1))
                .andExpect(jsonPath("$.expenseCount").value(4));
    }

    @Test
    @DisplayName("savings rate is null rather than zero when there was no income")
    void savingsRateIsNullWithoutIncome() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createTransaction(token, """
                {"type":"EXPENSE","amount":500,"occurredOn":"%s"}""".formatted(day(3)));

        // "Kept 0% of nothing" is a different claim from "kept none of what you earned",
        // and a chart that renders 0% would be asserting the second.
        mockMvc.perform(authed(get("/api/v1/analytics/summary"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(0))
                .andExpect(jsonPath("$.savingsRate").doesNotExist());
    }

    @Test
    @DisplayName("an empty period reports zeros rather than failing")
    void handlesEmptyPeriod() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(get("/api/v1/analytics/summary?from=2020-01-01&to=2020-01-31"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(0))
                .andExpect(jsonPath("$.totalExpense").value(0))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.averageDailyExpense").value(0));
    }

    @Test
    @DisplayName("the category breakdown is ordered largest first and keeps uncategorised spend")
    void breaksDownByCategory() throws Exception {
        String token = seed();

        JsonNode body = json(mockMvc.perform(authed(get("/api/v1/analytics/by-category"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.total").value(39400))
                .andReturn());

        JsonNode slices = body.get("slices");
        assertThat(slices).hasSize(4);
        assertThat(slices.get(0).get("categoryName").asString()).isEqualTo("Rent");
        assertThat(slices.get(0).get("share").decimalValue()).isEqualByComparingTo("76.1");

        // The uncategorised 450 is a named slice, not silently dropped. It carries no
        // categoryId at all - the API omits null fields globally rather than emitting them.
        JsonNode last = slices.get(3);
        assertThat(last.get("categoryName").asString()).isEqualTo("Uncategorised");
        assertThat(last.get("categoryId")).isNull();
        assertThat(last.get("amount").decimalValue()).isEqualByComparingTo("450");

        // Slice amounts must reconcile to the reported total even though the rounded
        // percentages need not sum to exactly 100.
        var sum = java.math.BigDecimal.ZERO;
        for (JsonNode slice : slices) {
            sum = sum.add(slice.get("amount").decimalValue());
        }
        assertThat(sum).isEqualByComparingTo("39400");
    }

    @Test
    @DisplayName("cashflow returns every month in the range, including quiet ones")
    void fillsCashflowGaps() throws Exception {
        String token = seed();

        String from = THIS_MONTH.minusMonths(3).atDay(1).toString();
        String to = THIS_MONTH.atEndOfMonth().toString();

        JsonNode body = json(mockMvc.perform(
                        authed(get("/api/v1/analytics/cashflow?from=" + from + "&to=" + to), token))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode points = body.get("points");
        // Four calendar months inclusive, two of which have no activity at all.
        assertThat(points).hasSize(4);
        assertThat(points.get(0).get("label").asString())
                .isEqualTo(THIS_MONTH.minusMonths(3).toString());
        assertThat(points.get(0).get("income").decimalValue()).isEqualByComparingTo("0");
        assertThat(points.get(0).get("expense").decimalValue()).isEqualByComparingTo("0");

        JsonNode current = points.get(3);
        assertThat(current.get("label").asString()).isEqualTo(THIS_MONTH.toString());
        assertThat(current.get("net").decimalValue()).isEqualByComparingTo("45600");

        // 45600 this month + 23000 last month, the two earlier months contributing nothing.
        assertThat(body.get("netTotal").decimalValue()).isEqualByComparingTo("68600");
    }

    @Test
    @DisplayName("the dashboard answers with the whole screen in one call")
    void assemblesDashboard() throws Exception {
        String token = seed();

        mockMvc.perform(authed(get("/api/v1/dashboard"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMonth.balance").value(45600))
                .andExpect(jsonPath("$.previousMonth.balance").value(23000))
                .andExpect(jsonPath("$.topExpenseCategories.slices.length()").value(4))
                .andExpect(jsonPath("$.cashflow.points.length()").value(6))
                .andExpect(jsonPath("$.recentTransactions.length()").value(7))
                .andExpect(jsonPath("$.budgetSet").value(false))
                .andExpect(jsonPath("$.alerts.length()").value(0));
    }

    @Test
    @DisplayName("dashboard alerts list only breached budget items, worst first")
    void ordersAlertsBySeverity() throws Exception {
        String token = seed();
        UUID groceries = globalCategoryId(token, "Groceries");
        UUID transport = globalCategoryId(token, "Transport");
        UUID rent = globalCategoryId(token, "Rent");

        // Groceries 7450/8000 -> 93.1% WARNING, Transport 1500/1200 -> 125% EXCEEDED,
        // Rent 30000/35000 -> 85.7% WARNING, Salary is income and cannot be budgeted.
        mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":%d,"month":%d,"items":[
                                  {"categoryId":"%s","limitAmount":8000},
                                  {"categoryId":"%s","limitAmount":1200},
                                  {"categoryId":"%s","limitAmount":35000}]}
                                """.formatted(THIS_MONTH.getYear(), THIS_MONTH.getMonthValue(),
                                groceries, transport, rent)))
                .andExpect(status().isCreated());

        mockMvc.perform(authed(get("/api/v1/dashboard"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetSet").value(true))
                .andExpect(jsonPath("$.alerts.length()").value(3))
                // EXCEEDED outranks WARNING, then the worse overrun first.
                .andExpect(jsonPath("$.alerts[0].category.name").value("Transport"))
                .andExpect(jsonPath("$.alerts[0].status").value("EXCEEDED"))
                .andExpect(jsonPath("$.alerts[1].category.name").value("Groceries"))
                .andExpect(jsonPath("$.alerts[1].percentUsed").value(93.1))
                .andExpect(jsonPath("$.alerts[2].category.name").value("Rent"));
    }

    @Test
    @DisplayName("an inverted or oversized range is rejected")
    void validatesRange() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(get("/api/v1/analytics/summary?from=2026-09-01&to=2026-08-01"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(authed(get("/api/v1/analytics/summary?from=2000-01-01&to=2026-12-31"), token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("analytics never cross user boundaries")
    void isolatesUsers() throws Exception {
        seed();
        String other = registerAndLogin("someone-else@example.com");

        mockMvc.perform(authed(get("/api/v1/analytics/summary"), other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(0))
                .andExpect(jsonPath("$.totalExpense").value(0));

        mockMvc.perform(authed(get("/api/v1/dashboard"), other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentTransactions.length()").value(0));
    }

    @Test
    @DisplayName("analytics require authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/analytics/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the default range is the current calendar month")
    void defaultsToCurrentMonth() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(get("/api/v1/analytics/summary"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(THIS_MONTH.atDay(1).toString()))
                .andExpect(jsonPath("$.to").value(THIS_MONTH.atEndOfMonth().toString()));

        assertThat(LocalDate.parse(THIS_MONTH.atEndOfMonth().toString()))
                .isAfterOrEqualTo(THIS_MONTH.atDay(1));
    }
}
