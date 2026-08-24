package com.fintrack.api.security;

import com.fintrack.api.config.JwtProperties;
import com.fintrack.api.model.Role;
import com.fintrack.api.model.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit tests - no Spring context, no database. The signing logic is pure computation,
 * so exercising it takes milliseconds and the failures point straight at the cause.
 */
class JwtServiceTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("a-test-secret-that-is-long-enough-for-hs256!".getBytes());

    private final JwtService jwtService = new JwtService(
            new JwtProperties(SECRET, "fintrack-test", Duration.ofMinutes(15), Duration.ofDays(30)));

    private static User user() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("hamed@example.com")
                .name("Hamed")
                .role(Role.USER)
                .baseCurrency("DZD")
                .build();
    }

    @Test
    @DisplayName("a freshly issued token round-trips back to the same user")
    void issuesAndParsesToken() {
        User user = user();

        String token = jwtService.issueAccessToken(user);
        Optional<Claims> claims = jwtService.parseAccessToken(token);

        assertThat(claims).isPresent();
        assertThat(jwtService.userId(claims.get())).contains(user.getId());
        assertThat(claims.get().get("email", String.class)).isEqualTo("hamed@example.com");
        assertThat(claims.get().get("role", String.class)).isEqualTo("USER");
        assertThat(claims.get().getIssuer()).isEqualTo("fintrack-test");
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void rejectsForeignSignature() {
        String foreignSecret =
                Base64.getEncoder().encodeToString("a-completely-different-secret-of-good-length!".getBytes());
        JwtService other = new JwtService(
                new JwtProperties(foreignSecret, "fintrack-test", Duration.ofMinutes(15), Duration.ofDays(30)));

        String forged = other.issueAccessToken(user());

        assertThat(jwtService.parseAccessToken(forged)).isEmpty();
    }

    @Test
    @DisplayName("a token from a different issuer is rejected")
    void rejectsForeignIssuer() {
        JwtService other = new JwtService(
                new JwtProperties(SECRET, "some-other-app", Duration.ofMinutes(15), Duration.ofDays(30)));

        // Same signing key, different iss - so the signature verifies but the issuer check
        // must still reject it. This is what stops a token minted by a sibling service that
        // happens to share the secret from being accepted here.
        String token = other.issueAccessToken(user());

        assertThat(jwtService.parseAccessToken(token)).isEmpty();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpiredToken() {
        JwtService instantlyExpiring = new JwtService(
                new JwtProperties(SECRET, "fintrack-test", Duration.ofSeconds(-1), Duration.ofDays(30)));

        String token = instantlyExpiring.issueAccessToken(user());

        assertThat(jwtService.parseAccessToken(token)).isEmpty();
    }

    @Test
    @DisplayName("a tampered payload is rejected")
    void rejectsTamperedToken() {
        String token = jwtService.issueAccessToken(user());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertThat(jwtService.parseAccessToken(tampered)).isEmpty();
    }

    @Test
    @DisplayName("garbage, empty and null inputs are rejected rather than thrown on")
    void rejectsMalformedInput() {
        assertThat(jwtService.parseAccessToken(null)).isEmpty();
        assertThat(jwtService.parseAccessToken("")).isEmpty();
        assertThat(jwtService.parseAccessToken("   ")).isEmpty();
        assertThat(jwtService.parseAccessToken("not.a.jwt")).isEmpty();
        assertThat(jwtService.parseAccessToken("eyJhbGciOiJub25lIn0..")).isEmpty();
    }

    @Test
    @DisplayName("a secret shorter than 256 bits is refused at construction with actionable advice")
    void refusesWeakSecret() {
        String tooShort = Base64.getEncoder().encodeToString("short".getBytes());

        assertThatThrownBy(() -> new JwtService(
                new JwtProperties(tooShort, "fintrack-test", Duration.ofMinutes(15), Duration.ofDays(30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 256 bits")
                .hasMessageContaining("openssl rand -base64 48");
    }

    @Test
    @DisplayName("expiresIn matches the configured access-token lifetime")
    void reportsConfiguredTtl() {
        assertThat(jwtService.accessTokenTtlSeconds()).isEqualTo(900);
    }
}
