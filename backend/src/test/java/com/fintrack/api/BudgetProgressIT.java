package com.fintrack.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BudgetProgressIT extends AbstractIntegrationTest {

    /**
     * Sets up August 2026: Groceries 7450, Transport 1500, 450 uncategorised, 85000 income,
     * plus a 5000 expense in July that must not leak into August's figures.
     */
    private String seedAugust(String token) throws Exception {
        UUID groceries = globalCategoryId(token, "Groceries");
        UUID transport = globalCategoryId(token, "Transport");
        UUID salary = globalCategoryId(token, "Salary");

        createTransaction(token, """
                {"type":"EXPENSE","amount":2450,"categoryId":"%s","occurredOn":"2026-08-03"}
                """.formatted(groceries));
        createTransaction(token, """
                {"type":"EXPENSE","amount":3200,"categoryId":"%s","occurredOn":"2026-08-05"}
                """.formatted(groceries));
        createTransaction(token, """
                {"type":"EXPENSE","amount":1800,"categoryId":"%s","occurredOn":"2026-08-12"}
                """.formatted(groceries));
        createTransaction(token, """
                {"type":"EXPENSE","amount":900,"categoryId":"%s","occurredOn":"2026-08-08"}
                """.formatted(transport));
        createTransaction(token, """
                {"type":"EXPENSE","amount":600,"categoryId":"%s","occurredOn":"2026-08-15"}
                """.formatted(transport));
        createTransaction(token, """
                {"type":"EXPENSE","amount":450,"occurredOn":"2026-08-18"}""");
        createTransaction(token, """
                {"type":"INCOME","amount":85000,"categoryId":"%s","occurredOn":"2026-08-01"}
                """.formatted(salary));
        // Outside the period. If this shows up in August's totals, the date filtering is wrong.
        createTransaction(token, """
                {"type":"EXPENSE","amount":5000,"categoryId":"%s","occurredOn":"2026-07-10"}
                """.formatted(groceries));

        return token;
    }

    private JsonNode createAugustBudget(String token) throws Exception {
        UUID groceries = globalCategoryId(token, "Groceries");
        UUID transport = globalCategoryId(token, "Transport");
        UUID rent = globalCategoryId(token, "Rent");

        return json(mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":8,"items":[
                                  {"categoryId":"%s","limitAmount":8000},
                                  {"categoryId":"%s","limitAmount":1200},
                                  {"categoryId":"%s","limitAmount":30000}]}
                                """.formatted(groceries, transport, rent)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private static JsonNode itemFor(JsonNode budget, String categoryName) {
        for (JsonNode item : budget.get("items")) {
            if (categoryName.equals(item.get("category").get("name").asString())) {
                return item;
            }
        }
        throw new AssertionError("No budget item for " + categoryName);
    }

    @Test
    @DisplayName("each item reports spend, remaining, percentage and status")
    void computesPerItemProgress() throws Exception {
        String token = seedAugust(registerAndLogin("hamed@example.com"));
        JsonNode budget = createAugustBudget(token);

        JsonNode groceries = itemFor(budget, "Groceries");
        assertThat(groceries.get("spent").decimalValue()).isEqualByComparingTo("7450");
        assertThat(groceries.get("remaining").decimalValue()).isEqualByComparingTo("550");
        assertThat(groceries.get("percentUsed").decimalValue()).isEqualByComparingTo("93.1");
        assertThat(groceries.get("status").asString()).isEqualTo("WARNING");

        JsonNode transport = itemFor(budget, "Transport");
        assertThat(transport.get("spent").decimalValue()).isEqualByComparingTo("1500");
        // Negative remaining is deliberate: it says how far over the limit the month went.
        assertThat(transport.get("remaining").decimalValue()).isEqualByComparingTo("-300");
        assertThat(transport.get("percentUsed").decimalValue()).isEqualByComparingTo("125.0");
        assertThat(transport.get("status").asString()).isEqualTo("EXCEEDED");

        JsonNode rent = itemFor(budget, "Rent");
        assertThat(rent.get("spent").decimalValue()).isEqualByComparingTo("0");
        assertThat(rent.get("status").asString()).isEqualTo("ON_TRACK");
    }

    @Test
    @DisplayName("spend outside the budgeted month is excluded")
    void respectsPeriodBoundaries() throws Exception {
        String token = seedAugust(registerAndLogin("hamed@example.com"));
        JsonNode budget = createAugustBudget(token);

        assertThat(budget.get("periodStart").asString()).isEqualTo("2026-08-01");
        assertThat(budget.get("periodEnd").asString()).isEqualTo("2026-08-31");
        // 7450, not 12450 — July's 5000 in the same category stays in July.
        assertThat(itemFor(budget, "Groceries").get("spent").decimalValue())
                .isEqualByComparingTo("7450");
    }

    @Test
    @DisplayName("totals surface uncategorised and unbudgeted spend rather than hiding it")
    void surfacesUntrackedSpend() throws Exception {
        String token = seedAugust(registerAndLogin("hamed@example.com"));
        UUID entertainment = globalCategoryId(token, "Entertainment");
        createAugustBudget(token);

        // Spend in a category the budget says nothing about.
        createTransaction(token, """
                {"type":"EXPENSE","amount":2000,"categoryId":"%s","occurredOn":"2026-08-20"}
                """.formatted(entertainment));

        mockMvc.perform(authed(get("/api/v1/budgets/2026/8"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.totalLimit").value(39200))
                .andExpect(jsonPath("$.totals.totalSpent").value(8950))
                .andExpect(jsonPath("$.totals.uncategorisedSpend").value(450))
                .andExpect(jsonPath("$.totals.unbudgetedSpend").value(2000))
                .andExpect(jsonPath("$.totals.totalIncome").value(85000))
                .andExpect(jsonPath("$.totals.itemsWarning").value(1))
                .andExpect(jsonPath("$.totals.itemsExceeded").value(1));
    }

    @Test
    @DisplayName("the alert fires at the threshold, not just past it")
    void alertsAtExactThreshold() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");

        // Exactly 80% of 1000.
        createTransaction(token, """
                {"type":"EXPENSE","amount":800,"categoryId":"%s","occurredOn":"2026-08-10"}
                """.formatted(groceries));

        JsonNode budget = json(mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":8,"items":[
                                  {"categoryId":"%s","limitAmount":1000,"alertThreshold":0.80}]}
                                """.formatted(groceries)))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(itemFor(budget, "Groceries").get("status").asString()).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("only expense categories can be budgeted")
    void rejectsIncomeCategory() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID salary = globalCategoryId(token, "Salary");

        mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":9,"items":[{"categoryId":"%s","limitAmount":100}]}
                                """.formatted(salary)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a month can only have one budget, and a category only one line in it")
    void rejectsDuplicates() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");

        mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":8}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":8}"""))
                .andExpect(status().isConflict());

        mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":9,"items":[
                                  {"categoryId":"%s","limitAmount":100},
                                  {"categoryId":"%s","limitAmount":200}]}
                                """.formatted(groceries, groceries)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an out-of-range month is rejected and a missing budget is a 404")
    void validatesPeriod() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(post("/api/v1/budgets"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":13}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(get("/api/v1/budgets/2026/3"), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("raising a limit moves an item out of EXCEEDED without touching transactions")
    void updatingLimitRecomputesStatus() throws Exception {
        String token = seedAugust(registerAndLogin("hamed@example.com"));
        JsonNode budget = createAugustBudget(token);
        String transportItemId = itemFor(budget, "Transport").get("id").asString();

        mockMvc.perform(authed(patch("/api/v1/budget-items/" + transportItemId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limitAmount":5000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spent").value(1500))
                .andExpect(jsonPath("$.remaining").value(3500))
                .andExpect(jsonPath("$.status").value("ON_TRACK"));
    }

    @Test
    @DisplayName("removing an item drops it from the budget but keeps its transactions")
    void deletingItemKeepsTransactions() throws Exception {
        String token = seedAugust(registerAndLogin("hamed@example.com"));
        JsonNode budget = createAugustBudget(token);
        String transportItemId = itemFor(budget, "Transport").get("id").asString();

        mockMvc.perform(authed(delete("/api/v1/budget-items/" + transportItemId), token))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(get("/api/v1/budgets/2026/8"), token))
                .andExpect(jsonPath("$.items.length()").value(2))
                // Transport spend is now unbudgeted rather than gone.
                .andExpect(jsonPath("$.totals.unbudgetedSpend").value(1500));

        mockMvc.perform(authed(get("/api/v1/transactions?type=EXPENSE&size=50"), token))
                .andExpect(jsonPath("$.meta.total").value(7));
    }

    @Test
    @DisplayName("one user's budget is invisible to another")
    void isolatesUsers() throws Exception {
        String hamed = seedAugust(registerAndLogin("hamed@example.com"));
        JsonNode budget = createAugustBudget(hamed);
        String budgetId = budget.get("id").asString();
        String itemId = itemFor(budget, "Groceries").get("id").asString();

        String other = registerAndLogin("someone-else@example.com");

        mockMvc.perform(authed(get("/api/v1/budgets/2026/8"), other))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/budgets/" + budgetId), other))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(patch("/api/v1/budget-items/" + itemId), other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limitAmount":1}"""))
                .andExpect(status().isNotFound());
    }
}
