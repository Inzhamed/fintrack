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

class TransactionFlowIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("a created transaction defaults its date to today and its currency to the account's")
    void appliesDefaults() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");

        JsonNode created = createTransaction(token, """
                {"type":"EXPENSE","amount":2450.00,"categoryId":"%s","description":"Weekly shop"}
                """.formatted(groceries));

        assertThat(created.get("currency").asString()).isEqualTo("DZD");
        assertThat(created.get("occurredOn").asString()).isNotBlank();
        assertThat(created.get("createdAt").asString()).isNotBlank();
        // The category is inlined so a list view can render a chip without a second call.
        assertThat(created.get("category").get("name").asString()).isEqualTo("Groceries");
        assertThat(created.get("category").get("color").asString()).isEqualTo("#22c55e");
    }

    @Test
    @DisplayName("an expense cannot be filed under an income category")
    void rejectsMismatchedCategoryDirection() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID salary = globalCategoryId(token, "Salary");

        mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"EXPENSE","amount":100,"categoryId":"%s"}
                                """.formatted(salary)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.expected").value("EXPENSE"));
    }

    @Test
    @DisplayName("amount must be positive and the date cannot be in the future")
    void enforcesAmountAndDateRules() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"EXPENSE","amount":-50}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.amount").exists());

        mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"EXPENSE","amount":50,"occurredOn":"2099-01-01"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.occurredOn").exists());
    }

    @Test
    @DisplayName("one user's transactions are invisible to another")
    void isolatesUsers() throws Exception {
        String hamed = registerAndLogin("hamed@example.com");
        String other = registerAndLogin("someone-else@example.com");

        JsonNode mine = createTransaction(hamed, """
                {"type":"EXPENSE","amount":999,"description":"Private"}""");
        String id = mine.get("id").asString();

        // Absent from the other user's list...
        mockMvc.perform(authed(get("/api/v1/transactions"), other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(0));

        // ...and unreachable by direct id. 404 rather than 403, so the response does not
        // confirm that the id exists at all.
        mockMvc.perform(authed(get("/api/v1/transactions/" + id), other))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(patch("/api/v1/transactions/" + id), other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1}"""))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(delete("/api/v1/transactions/" + id), other))
                .andExpect(status().isNotFound());

        // Still intact for its owner.
        mockMvc.perform(authed(get("/api/v1/transactions/" + id), hamed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(999));
    }

    @Test
    @DisplayName("filters combine with AND and never widen past the caller's own rows")
    void filtersResults() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");
        UUID transport = globalCategoryId(token, "Transport");
        UUID salary = globalCategoryId(token, "Salary");

        createTransaction(token, """
                {"type":"EXPENSE","amount":3200,"categoryId":"%s","description":"Groceries","merchant":"Uno","occurredOn":"2026-08-05"}
                """.formatted(groceries));
        createTransaction(token, """
                {"type":"EXPENSE","amount":900,"categoryId":"%s","description":"Taxi","occurredOn":"2026-08-08"}
                """.formatted(transport));
        createTransaction(token, """
                {"type":"EXPENSE","amount":450,"description":"Unfiled","occurredOn":"2026-08-18"}""");
        createTransaction(token, """
                {"type":"INCOME","amount":85000,"categoryId":"%s","description":"Salary","occurredOn":"2026-08-01"}
                """.formatted(salary));
        createTransaction(token, """
                {"type":"EXPENSE","amount":5000,"categoryId":"%s","description":"July shop","occurredOn":"2026-07-10"}
                """.formatted(groceries));

        assertTotal(token, "?type=INCOME", 1);
        assertTotal(token, "?categoryId=" + groceries, 2);
        assertTotal(token, "?uncategorised=true", 1);
        assertTotal(token, "?minAmount=500&maxAmount=1000", 1);
        assertTotal(token, "?search=shop", 1);
        assertTotal(token, "?search=UNO", 1);                       // case-insensitive, merchant
        assertTotal(token, "?from=2026-08-01&to=2026-08-31", 4);    // July excluded
        assertTotal(token, "?from=2026-08-01&to=2026-08-31&type=EXPENSE&minAmount=1000", 1);
    }

    @Test
    @DisplayName("contradictory filters are rejected rather than silently resolved")
    void rejectsContradictoryFilters() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");

        mockMvc.perform(authed(get("/api/v1/transactions?uncategorised=true&categoryId=" + groceries), token))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(get("/api/v1/transactions?from=2026-09-01&to=2026-08-01"), token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the list is paginated with a stable newest-first order")
    void paginates() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        for (int day = 1; day <= 5; day++) {
            createTransaction(token, """
                    {"type":"EXPENSE","amount":%d,"occurredOn":"2026-08-%02d"}""".formatted(day * 100, day));
        }

        mockMvc.perform(authed(get("/api/v1/transactions?page=0&size=2"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(5))
                .andExpect(jsonPath("$.meta.totalPages").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                // Default sort is occurredOn DESC, so the 5th is first.
                .andExpect(jsonPath("$.data[0].occurredOn").value("2026-08-05"));

        mockMvc.perform(authed(get("/api/v1/transactions?page=2&size=2"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.hasNext").value(false))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("PATCH leaves omitted fields alone, and clearing a category is explicit")
    void patchesPartially() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");

        String id = createTransaction(token, """
                {"type":"EXPENSE","amount":100,"categoryId":"%s","description":"Original","merchant":"Shop"}
                """.formatted(groceries)).get("id").asString();

        // Changing only the amount must not blank the description or drop the category.
        mockMvc.perform(authed(patch("/api/v1/transactions/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":250}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(250))
                .andExpect(jsonPath("$.description").value("Original"))
                .andExpect(jsonPath("$.merchant").value("Shop"))
                .andExpect(jsonPath("$.category.name").value("Groceries"));

        // A null categoryId means "unchanged", so removal needs its own flag.
        mockMvc.perform(authed(patch("/api/v1/transactions/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clearCategory":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").doesNotExist());
    }

    @Test
    @DisplayName("deleting a category in use is refused unless forced, and never deletes transactions")
    void guardsCategoryDeletion() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        String categoryId = json(mockMvc.perform(authed(post("/api/v1/categories"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Coffee","type":"EXPENSE","color":"#7c3aed"}"""))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asString();

        createTransaction(token, """
                {"type":"EXPENSE","amount":300,"categoryId":"%s","description":"Espresso"}
                """.formatted(categoryId));

        mockMvc.perform(authed(delete("/api/v1/categories/" + categoryId), token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        mockMvc.perform(authed(delete("/api/v1/categories/" + categoryId + "?force=true"), token))
                .andExpect(status().isNoContent());

        // The transaction survives, merely uncategorised — history is never destroyed.
        mockMvc.perform(authed(get("/api/v1/transactions"), token))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].description").value("Espresso"))
                .andExpect(jsonPath("$.data[0].category").doesNotExist());
    }

    @Test
    @DisplayName("the seeded global categories are visible but not editable")
    void globalCategoriesAreReadOnly() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");

        mockMvc.perform(authed(get("/api/v1/categories?type=EXPENSE"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].global").value(true));

        mockMvc.perform(authed(patch("/api/v1/categories/" + groceries), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed"}"""))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(delete("/api/v1/categories/" + groceries), token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a duplicate category name for the same user and direction is rejected")
    void rejectsDuplicateCategory() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        String body = """
                {"name":"Coffee","type":"EXPENSE"}""";

        mockMvc.perform(authed(post("/api/v1/categories"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(authed(post("/api/v1/categories"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"coffee","type":"EXPENSE"}"""))   // differs only in case
                .andExpect(status().isConflict());
    }

    private void assertTotal(String token, String query, int expected) throws Exception {
        mockMvc.perform(authed(get("/api/v1/transactions" + query + "&size=50"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(expected));
    }
}
