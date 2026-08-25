package com.fintrack.api;

import com.fintrack.api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for integration tests: a real Postgres, a clean slate per test, and helpers
 * for registering a user and calling the API as them.
 * <p>
 * Every subclass shares one Spring context and one container — the context cache keys on
 * the annotations, so keeping them identical here is what stops each test class from
 * paying a fresh 15-second startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private BillRepository billRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void wipe() {
        // Order matters: children before parents, or the foreign keys refuse the delete.
        // Budget items go with their budgets via orphanRemoval.
        notificationRepository.deleteAll();
        billRepository.deleteAll();
        transactionRepository.deleteAll();
        budgetRepository.deleteAll();
        // Only user-owned categories. The globals are seeded by migration V2 and must
        // survive, since every test depends on them being there.
        categoryRepository.deleteAll(
                categoryRepository.findAll().stream().filter(c -> !c.isGlobal()).toList());
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        // Redis carries rate-limit counters and cached analytics across tests. Every test
        // registers from the same loopback address, so without this the register quota is
        // exhausted a few tests in and the rest fail with 429 - and a cached aggregate
        // from one test could answer the next.
        redisTemplate.execute((RedisConnection connection) -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    /** Registers a user and returns their access token. */
    protected String registerAndLogin(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct horse battery","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        return json(result).get("accessToken").asString();
    }

    /** Looks up a seeded global category by name. */
    protected UUID globalCategoryId(String token, String name) throws Exception {
        JsonNode categories = json(mockMvc.perform(authed(get("/api/v1/categories"), token))
                .andExpect(status().isOk())
                .andReturn());

        for (JsonNode category : categories) {
            if (name.equals(category.get("name").asString())) {
                return UUID.fromString(category.get("id").asString());
            }
        }
        throw new AssertionError("No seeded category named " + name);
    }

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder,
                                                   String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** POSTs a transaction and returns the created body. */
    protected JsonNode createTransaction(String token, String body) throws Exception {
        return json(mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
    }
}
