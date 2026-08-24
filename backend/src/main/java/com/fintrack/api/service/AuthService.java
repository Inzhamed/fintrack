package com.fintrack.api.service;

import com.fintrack.api.dto.auth.LoginRequest;
import com.fintrack.api.dto.auth.RegisterRequest;
import com.fintrack.api.dto.auth.TokenResponse;
import com.fintrack.api.dto.auth.UserResponse;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.exception.ErrorCode;
import com.fintrack.api.model.Role;
import com.fintrack.api.model.User;
import com.fintrack.api.repository.UserRepository;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Registration, login, token refresh and sign-out. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    /**
     * Creates an account and signs it straight in, so the client does not have to make a
     * second call with credentials it already holds.
     */
    @Transactional
    public TokenResponse register(RegisterRequest request, String userAgent, String ip) {
        String email = normaliseEmail(request.email());

        // Checked explicitly to return a clear 409. The unique index is still the real
        // guarantee - two concurrent registrations would race past this check, and the
        // resulting constraint violation is handled by GlobalExceptionHandler.
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "An account with this email already exists");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name().trim())
                .role(Role.USER)
                .baseCurrency(request.baseCurrencyOrDefault())
                .build();

        // saveAndFlush, not save: @CreationTimestamp is populated during flush, and the
        // response is built from this instance before the transaction commits.
        user = userRepository.saveAndFlush(user);
        log.info("Registered new user {}", user.getId());

        return issueTokens(user, userAgent, ip);
    }

    /**
     * Verifies credentials through the {@link AuthenticationManager} rather than comparing
     * hashes by hand, so that the configured encoder, the timing-attack countermeasures in
     * DaoAuthenticationProvider, and any future account-locking rules all apply.
     */
    @Transactional
    public TokenResponse login(LoginRequest request, String userAgent, String ip) {
        String email = normaliseEmail(request.email());

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));

            AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
            User user = userRepository.findById(principal.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS,
                            "Invalid email or password"));

            log.info("User {} signed in", user.getId());
            return issueTokens(user, userAgent, ip);

        } catch (AuthenticationException ex) {
            // Same answer for an unknown address and a wrong password.
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
    }

    /**
     * Exchanges a refresh token for a new pair. The old refresh token is consumed in the
     * process - see {@link RefreshTokenService#redeem}.
     */
    @Transactional
    public TokenResponse refresh(String refreshToken, String userAgent, String ip) {
        User user = refreshTokenService.redeem(refreshToken);
        return issueTokens(user, userAgent, ip);
    }

    /** The account behind the current access token. */
    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                // Reachable when a still-valid token outlives the account it names.
                .orElseThrow(() -> ApiException.notFound("User", userId));
    }

    /** Signs out the session belonging to this refresh token. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /** Signs out every session for the user, on every device. */
    @Transactional
    public void logoutEverywhere(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        int revoked = refreshTokenService.revokeAll(user);
        log.info("Revoked {} session(s) for user {}", revoked, userId);
    }

    private TokenResponse issueTokens(User user, String userAgent, String ip) {
        String accessToken = jwtService.issueAccessToken(user);
        String refreshToken = refreshTokenService.issue(user, userAgent, ip);

        return TokenResponse.of(
                accessToken, refreshToken,
                jwtService.accessTokenTtlSeconds(),
                UserResponse.from(user));
    }

    /**
     * Lowercased and trimmed. Uniqueness is enforced on {@code lower(email)}, so lookups
     * have to fold the same way or a user could register twice with different casing.
     */
    private static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
