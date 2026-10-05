package com.fintrack.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CachingAndRateLimitIT extends AbstractIntegrationTest {

    private static final YearMonth THIS_MONTH = YearMonth.now(FixedClockConfiguration.CLOCK);

    @Autowired
    private StringRedisTemplate redis;

    private static String day(int dayOfMonth) {
        return THIS_MONTH.atDay(dayOfMonth).toString();
    }

    // --- caching ------------------------------------------------------------------------

    @Test
    @DisplayName("an analytics read is cached, and the entry is scoped to one user")
    void cachesPerUser() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createTransaction(token, """
                {"type":"EXPENSE","amount":500,"occurredOn":"%s"}""".formatted(day(3)));

        mockMvc.perform(authed(get("/api/v1/analytics/summary"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpense").value(500));

        Set<String> keys = redis.keys("analytics:summary::*");
        assertThat(keys).hasSize(1);

        // The user id is the first segment of every key. Without it, two users asking for the
        // same date range would share an entry - one user's totals served to another.
        String key = keys.iterator().next();
        String userId = json(mockMvc.perform(authed(get("/api/v1/auth/me"), token)).andReturn())
                .get("id").asString();
        assertThat(key).isEqualTo("analytics:summary::" + userId + ":null:null");
    }

    @Test
    @DisplayName("two users never share a cached aggregate")
    void neverServesOneUsersTotalsToAnother() throws Exception {
        String hamed = registerAndLogin("hamed@example.com");
        createTransaction(hamed, """
                {"type":"EXPENSE","amount":9999,"occurredOn":"%s"}""".formatted(day(3)));

        // Populates the cache for the first user.
        mockMvc.perform(authed(get("/api/v1/analytics/summary"), hamed))
                .andExpect(jsonPath("$.totalExpense").value(9999));

        // Same endpoint, same (default) date range, different caller.
        String other = registerAndLogin("someone-else@example.com");
        mockMvc.perform(authed(get("/api/v1/analytics/summary"), other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpense").value(0));

        assertThat(redis.keys("analytics:summary::*")).hasSize(2);
    }

    @Test
    @DisplayName("adding a transaction evicts that user's cached analytics")
    void evictsOnWrite() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createTransaction(token, """
                {"type":"EXPENSE","amount":100,"occurredOn":"%s"}""".formatted(day(3)));

        mockMvc.perform(authed(get("/api/v1/analytics/summary"), token))
                .andExpect(jsonPath("$.totalExpense").value(100));
        assertThat(redis.keys("analytics:summary::*")).isNotEmpty();

        createTransaction(token, """
                {"type":"EXPENSE","amount":250,"occurredOn":"%s"}""".formatted(day(4)));

        // The write cleared the entry rather than leaving it to expire, so the next read
        // recomputes instead of serving a total that is now wrong.
        assertThat(redis.keys("analytics:summary::*")).isEmpty();

        mockMvc.perform(authed(get("/api/v1/analytics/summary"), token))
                .andExpect(jsonPath("$.totalExpense").value(350));
    }

    @Test
    @DisplayName("one user's write does not evict another user's cache")
    void evictionIsScopedToTheWriter() throws Exception {
        String hamed = registerAndLogin("hamed@example.com");
        String other = registerAndLogin("someone-else@example.com");

        mockMvc.perform(authed(get("/api/v1/analytics/summary"), hamed)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/analytics/summary"), other)).andExpect(status().isOk());
        assertThat(redis.keys("analytics:summary::*")).hasSize(2);

        createTransaction(other, """
                {"type":"EXPENSE","amount":10,"occurredOn":"%s"}""".formatted(day(5)));

        // Evicting everything on every write would be simpler and would turn the cache into a
        // miss generator at any real user count. Only the writer's entry goes.
        assertThat(redis.keys("analytics:summary::*")).hasSize(1);
    }

    @Test
    @DisplayName("deleting a category evicts analytics, since spend moves to uncategorised")
    void evictsWhenCategoryChanges() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        String categoryId = json(mockMvc.perform(authed(post("/api/v1/categories"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Coffee","type":"EXPENSE"}"""))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asString();

        createTransaction(token, """
                {"type":"EXPENSE","amount":300,"categoryId":"%s","occurredOn":"%s"}
                """.formatted(categoryId, day(6)));

        JsonNode before = json(mockMvc.perform(authed(get("/api/v1/analytics/by-category"), token))
                .andExpect(status().isOk()).andReturn());
        assertThat(before.get("slices").get(0).get("categoryName").asString()).isEqualTo("Coffee");

        mockMvc.perform(authed(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .delete("/api/v1/categories/" + categoryId + "?force=true"), token))
                .andExpect(status().isNoContent());

        JsonNode after = json(mockMvc.perform(authed(get("/api/v1/analytics/by-category"), token))
                .andExpect(status().isOk()).andReturn());
        assertThat(after.get("slices").get(0).get("categoryName").asString())
                .isEqualTo("Uncategorised");
    }

    // --- rate limiting ------------------------------------------------------------------

    @Test
    @DisplayName("repeated failed logins are throttled with a 429 and a Retry-After")
    void throttlesFailedLogins() throws Exception {
        registerAndLogin("hamed@example.com");

        String wrongPassword = """
                {"email":"hamed@example.com","password":"wrong"}""";

        // The limit is 10 per window; the first ten wrong attempts are ordinary 401s.
        for (int attempt = 1; attempt <= 10; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(wrongPassword))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongPassword))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
                // The standard header, so a client can back off without parsing the body.
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("the limit counts attempts, so a correct password is refused once exhausted")
    void throttlingIgnoresWhetherTheGuessWasRight() throws Exception {
        registerAndLogin("hamed@example.com");

        for (int attempt = 1; attempt <= 11; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email":"hamed@example.com","password":"wrong"}"""));
        }

        // Deliberate: the counter is on attempts, not failures. Letting a correct password
        // through would turn the endpoint into an oracle that answers "was this guess right?"
        // at unlimited speed.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hamed@example.com","password":"correct horse battery"}"""))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a successful login clears the counter")
    void successResetsTheCounter() throws Exception {
        registerAndLogin("hamed@example.com");

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"hamed@example.com","password":"wrong"}"""))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hamed@example.com","password":"correct horse battery"}"""))
                .andExpect(status().isOk())
                // Still 4 on *this* response: the filter counts the request and writes the
                // header before the handler runs, so the reset the handler performs on success
                // cannot be reflected here. It shows up on the next request.
                .andExpect(header().string("X-RateLimit-Remaining", "4"));

        // The next attempt starts a fresh window: 10 minus this one request.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hamed@example.com","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-RateLimit-Remaining", "9"));

        // Someone who mistypes a few times, succeeds, then mistypes again is not left near
        // the limit - which without the reset they would be.
        for (int attempt = 1; attempt <= 8; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"hamed@example.com","password":"wrong"}"""))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("registration has its own tighter quota, separate from login")
    void registrationIsLimitedSeparately() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"user%d@example.com","password":"correct horse battery","name":"User"}
                                    """.formatted(attempt)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user6@example.com","password":"correct horse battery","name":"User"}"""))
                .andExpect(status().isTooManyRequests());

        // Buckets are per-path, so burning the register quota must not lock the caller out of
        // signing in to an account they already have.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user1@example.com","password":"correct horse battery"}"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("authenticated endpoints are not rate limited")
    void doesNotThrottleAuthenticatedTraffic() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        // Well past the auth limits. Requests behind a valid token are already bounded by
        // needing that token, so throttling them would only punish a busy legitimate client.
        for (int request = 1; request <= 30; request++) {
            mockMvc.perform(authed(get("/api/v1/transactions"), token))
                    .andExpect(status().isOk());
        }
    }
}
