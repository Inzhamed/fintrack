package com.fintrack.api;

import com.fintrack.api.repository.RefreshTokenRepository;
import com.fintrack.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the authentication flow against a real Postgres, exercising the
 * actual Flyway migrations.
 * <p>
 * These are the behaviours that are easy to get subtly wrong and impossible to notice by
 * clicking around: case-insensitive email uniqueness, indistinguishable failure responses,
 * and refresh-token rotation with reuse detection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void resetState() {
        // Each test starts from an empty account table. Categories seeded by V2 stay.
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static String registerBody(String email, String password) {
        return """
                {"email":"%s","password":"%s","name":"Hamed Inezarene"}
                """.formatted(email, password);
    }

    private JsonNode register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("registering returns a token pair and persists the account")
    void registersUser() throws Exception {
        JsonNode body = register("hamed@example.com", "correct horse battery");

        assertThat(body.get("accessToken").asString()).isNotBlank();
        assertThat(body.get("refreshToken").asString()).isNotBlank();
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asInt()).isEqualTo(900);

        // createdAt is only populated if the insert is flushed before the response is built.
        assertThat(body.get("user").get("createdAt").asString()).isNotBlank();
        assertThat(userRepository.existsByEmailIgnoreCase("hamed@example.com")).isTrue();
    }

    @Test
    @DisplayName("the response never contains the password or its hash")
    void neverLeaksPassword() throws Exception {
        JsonNode body = register("hamed@example.com", "correct horse battery");

        String raw = body.toString();
        assertThat(raw).doesNotContain("correct horse battery");
        assertThat(raw).doesNotContain("passwordHash");
        assertThat(raw).doesNotContain("$2a$");   // a BCrypt hash prefix
    }

    @Test
    @DisplayName("email is stored folded, so the same address cannot register twice in different case")
    void emailUniquenessIsCaseInsensitive() throws Exception {
        register("Hamed@Example.COM", "correct horse battery");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("hamed@example.com", "a different password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));

        assertThat(userRepository.findByEmailIgnoreCase("HAMED@EXAMPLE.COM")).isPresent();
    }

    @Test
    @DisplayName("a wrong password and an unknown account produce byte-identical responses")
    void failedLoginsAreIndistinguishable() throws Exception {
        register("hamed@example.com", "correct horse battery");

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hamed@example.com","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Compare everything but the timestamp, which naturally differs between the two calls.
        JsonNode a = objectMapper.readTree(wrongPassword).get("error");
        JsonNode b = objectMapper.readTree(unknownUser).get("error");
        assertThat(a.get("code")).isEqualTo(b.get("code"));
        assertThat(a.get("message")).isEqualTo(b.get("message"));
        assertThat(a.get("message").asString()).isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("a protected endpoint requires a token and accepts a valid one")
    void protectsEndpoints() throws Exception {
        JsonNode registered = register("hamed@example.com", "correct horse battery");
        String accessToken = registered.get("accessToken").asString();

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("hamed@example.com"));
    }

    @Test
    @DisplayName("refreshing rotates the token: the old one dies, a new one is issued")
    void refreshRotatesToken() throws Exception {
        JsonNode registered = register("hamed@example.com", "correct horse battery");
        String original = registered.get("refreshToken").asString();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(original)))
                .andExpect(status().isOk())
                .andReturn();

        String rotated = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .get("refreshToken").asString();

        assertThat(rotated).isNotEqualTo(original);
    }

    @Test
    @DisplayName("replaying a spent refresh token revokes every session for that user")
    void reuseOfSpentTokenRevokesWholeFamily() throws Exception {
        JsonNode registered = register("hamed@example.com", "correct horse battery");
        String original = registered.get("refreshToken").asString();

        // Rotate once: `original` is now spent and `rotated` is the live token.
        MvcResult first = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(original)))
                .andExpect(status().isOk())
                .andReturn();
        String rotated = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("refreshToken").asString();

        // Replay the spent token, as a thief holding a stolen copy would.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(original)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));

        // The legitimate holder's still-unused token must now be dead too. This is the
        // assertion that catches revoking-then-throwing inside one transaction, where the
        // rollback silently undoes the revocation.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(rotated)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("an unknown refresh token is rejected without revealing anything")
    void rejectsUnknownRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"not-a-real-token"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("logout revokes the session so its refresh token stops working")
    void logoutRevokesSession() throws Exception {
        JsonNode registered = register("hamed@example.com", "correct horse battery");
        String refreshToken = registered.get("refreshToken").asString();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("validation failures name every offending field at once")
    void reportsAllValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short","name":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.email").exists())
                .andExpect(jsonPath("$.error.details.password").exists())
                .andExpect(jsonPath("$.error.details.name").exists());
    }

    @Test
    @DisplayName("an unknown field is rejected rather than silently ignored")
    void rejectsUnknownFields() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hamed@example.com","password":"correct horse battery",
                                 "name":"Hamed","role":"ADMIN"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }
}
