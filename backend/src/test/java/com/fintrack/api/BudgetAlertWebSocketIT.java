package com.fintrack.api;

import com.fintrack.api.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a real WebSocket against a running server.
 * <p>
 * Uses {@code RANDOM_PORT} rather than MockMvc: MockMvc never starts a servlet container, so
 * there is no socket to upgrade and the whole STOMP path - handshake, CONNECT authentication,
 * user-destination routing - would go untested.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class BudgetAlertWebSocketIT {

    private static final YearMonth THIS_MONTH = YearMonth.now();

    @LocalServerPort
    private int port;

    @Autowired private JsonMapper jsonMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;

    private RestClient http;

    @BeforeEach
    void reset() {
        transactionRepository.deleteAll();
        budgetRepository.deleteAll();
        categoryRepository.deleteAll(
                categoryRepository.findAll().stream().filter(c -> !c.isGlobal()).toList());
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.execute((RedisConnection connection) -> {
            connection.serverCommands().flushDb();
            return null;
        });

        http = RestClient.create("http://localhost:" + port);
    }

    // --- helpers -------------------------------------------------------------------------

    private String register(String email) {
        JsonNode body = http.post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body("""
                        {"email":"%s","password":"correct horse battery","name":"Test"}"""
                        .formatted(email))
                .retrieve()
                .body(JsonNode.class);
        return body.get("accessToken").asString();
    }

    private UUID categoryId(String token, String name) {
        JsonNode categories = http.get().uri("/api/v1/categories")
                .header("Authorization", "Bearer " + token)
                .retrieve().body(JsonNode.class);
        for (JsonNode category : categories) {
            if (name.equals(category.get("name").asString())) {
                return UUID.fromString(category.get("id").asString());
            }
        }
        throw new AssertionError("No category " + name);
    }

    private void createBudget(String token, UUID category, String limit) {
        http.post().uri("/api/v1/budgets")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body("""
                        {"year":%d,"month":%d,"items":[{"categoryId":"%s","limitAmount":%s}]}"""
                        .formatted(THIS_MONTH.getYear(), THIS_MONTH.getMonthValue(), category, limit))
                .retrieve().toBodilessEntity();
    }

    private void spend(String token, UUID category, String amount) {
        http.post().uri("/api/v1/transactions")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body("""
                        {"type":"EXPENSE","amount":%s,"categoryId":"%s","occurredOn":"%s"}"""
                        .formatted(amount, category, THIS_MONTH.atDay(5)))
                .retrieve().toBodilessEntity();
    }

    /** Connects, authenticating with the token on the CONNECT frame as a browser would. */
    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter(jsonMapper));

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        return client.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeadersStub(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
    }

    /** Subscribes to the caller's own notification queue and captures the first message. */
    private BlockingQueue<JsonNode> subscribe(StompSession session) {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            @NonNull
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        return received;
    }

    /** Empty headers for the handshake; the token travels on the STOMP frame instead. */
    private static class WebSocketHttpHeadersStub
            extends org.springframework.web.socket.WebSocketHttpHeaders {}

    // --- tests ---------------------------------------------------------------------------

    @Test
    @DisplayName("crossing the alert threshold pushes a warning to the owner")
    void pushesWarningWhenThresholdCrossed() throws Exception {
        String token = register("hamed@example.com");
        UUID groceries = categoryId(token, "Groceries");
        createBudget(token, groceries, "1000");

        StompSession session = connect(token);
        BlockingQueue<JsonNode> received = subscribe(session);

        // 850 of 1000 is 85%, past the default 80% threshold but under the limit.
        spend(token, groceries, "850");

        JsonNode message = received.poll(10, TimeUnit.SECONDS);
        assertThat(message).as("expected a budget alert").isNotNull();
        assertThat(message.get("type").asString()).isEqualTo("BUDGET_THRESHOLD");
        assertThat(message.get("data").get("status").asString()).isEqualTo("WARNING");
        assertThat(message.get("data").get("categoryName").asString()).isEqualTo("Groceries");
        assertThat(message.get("message").asString()).contains("Groceries");

        session.disconnect();
    }

    @Test
    @DisplayName("passing the limit pushes an EXCEEDED alert")
    void pushesExceededWhenLimitPassed() throws Exception {
        String token = register("hamed@example.com");
        UUID transport = categoryId(token, "Transport");
        createBudget(token, transport, "1000");

        StompSession session = connect(token);
        BlockingQueue<JsonNode> received = subscribe(session);

        spend(token, transport, "1200");

        JsonNode message = received.poll(10, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        assertThat(message.get("data").get("status").asString()).isEqualTo("EXCEEDED");
        assertThat(message.get("title").asString()).startsWith("Over budget");

        session.disconnect();
    }

    @Test
    @DisplayName("spending below the threshold pushes nothing")
    void staysQuietBelowThreshold() throws Exception {
        String token = register("hamed@example.com");
        UUID groceries = categoryId(token, "Groceries");
        createBudget(token, groceries, "1000");

        StompSession session = connect(token);
        BlockingQueue<JsonNode> received = subscribe(session);

        spend(token, groceries, "100");

        assertThat(received.poll(3, TimeUnit.SECONDS))
                .as("10% of budget should not alert")
                .isNull();

        session.disconnect();
    }

    @Test
    @DisplayName("an already-breached category does not alert again on every purchase")
    void alertsOnTransitionOnly() throws Exception {
        String token = register("hamed@example.com");
        UUID groceries = categoryId(token, "Groceries");
        createBudget(token, groceries, "1000");

        StompSession session = connect(token);
        BlockingQueue<JsonNode> received = subscribe(session);

        spend(token, groceries, "850");                       // ON_TRACK -> WARNING, alerts
        assertThat(received.poll(10, TimeUnit.SECONDS)).isNotNull();

        spend(token, groceries, "20");                        // still WARNING, no transition
        assertThat(received.poll(3, TimeUnit.SECONDS))
                .as("no second alert while the status is unchanged")
                .isNull();

        spend(token, groceries, "200");                       // WARNING -> EXCEEDED, alerts
        JsonNode exceeded = received.poll(10, TimeUnit.SECONDS);
        assertThat(exceeded).isNotNull();
        assertThat(exceeded.get("data").get("status").asString()).isEqualTo("EXCEEDED");

        session.disconnect();
    }

    @Test
    @DisplayName("one user's alert never reaches another user's session")
    void neverDeliversAcrossUsers() throws Exception {
        String hamed = register("hamed@example.com");
        String other = register("someone-else@example.com");

        UUID groceries = categoryId(hamed, "Groceries");
        createBudget(hamed, groceries, "1000");

        // The eavesdropper subscribes to their own queue, which is the only one they can name.
        StompSession eavesdropper = connect(other);
        BlockingQueue<JsonNode> theirMessages = subscribe(eavesdropper);

        StompSession owner = connect(hamed);
        BlockingQueue<JsonNode> ownerMessages = subscribe(owner);

        spend(hamed, groceries, "900");

        assertThat(ownerMessages.poll(10, TimeUnit.SECONDS))
                .as("the owner receives their own alert")
                .isNotNull();
        assertThat(theirMessages.poll(3, TimeUnit.SECONDS))
                .as("another user must never receive it")
                .isNull();

        owner.disconnect();
        eavesdropper.disconnect();
    }

    @Test
    @DisplayName("a connection without a valid token is refused")
    void rejectsUnauthenticatedConnections() {
        assertThatThrownBy(() -> connect("not-a-real-token"))
                .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    }

    @Test
    @DisplayName("spending in an unbudgeted category pushes nothing")
    void staysQuietWithoutABudgetLine() throws Exception {
        String token = register("hamed@example.com");
        UUID groceries = categoryId(token, "Groceries");
        UUID transport = categoryId(token, "Transport");
        createBudget(token, groceries, "1000");

        StompSession session = connect(token);
        BlockingQueue<JsonNode> received = subscribe(session);

        // Transport has no limit in this budget, so there is no threshold to cross.
        spend(token, transport, "99999");

        assertThat(received.poll(3, TimeUnit.SECONDS)).isNull();
        session.disconnect();
    }

    @Test
    @DisplayName("income never triggers a budget alert")
    void ignoresIncome() throws Exception {
        String token = register("hamed@example.com");
        UUID groceries = categoryId(token, "Groceries");
        UUID salary = categoryId(token, "Salary");
        createBudget(token, groceries, "1000");

        StompSession session = connect(token);
        BlockingQueue<JsonNode> received = subscribe(session);

        http.post().uri("/api/v1/transactions")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body("""
                        {"type":"INCOME","amount":50000,"categoryId":"%s","occurredOn":"%s"}"""
                        .formatted(salary, THIS_MONTH.atDay(5)))
                .retrieve().toBodilessEntity();

        assertThat(received.poll(3, TimeUnit.SECONDS)).isNull();
        session.disconnect();
    }
}
