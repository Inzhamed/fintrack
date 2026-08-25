package com.fintrack.api.security;

import com.fintrack.api.exception.ApiError;
import com.fintrack.api.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Throttles the unauthenticated auth endpoints.
 * <p>
 * These are the only routes reachable without a token, which makes them the ones worth
 * guessing against: login for credential stuffing, register for mass account creation,
 * refresh for token probing. Everything behind authentication is already bounded by needing
 * a valid token in the first place.
 * <p>
 * Keyed on client IP. That is imperfect - a shared NAT counts as one caller, and an attacker
 * with many addresses gets an allowance per address - but it is the only identifier available
 * before authentication. Keying on the submitted email instead would let an attacker lock a
 * victim out of their own account by failing logins on their behalf.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    /**
     * Limits are configurable rather than constants.
     * <p>
     * The defaults are the production values - generous enough for someone who forgot their
     * password, tight enough to slow credential stuffing. They are overridable because an
     * end-to-end suite runs dozens of sign-ins from one address in a minute and would
     * otherwise be throttled by the very protection it is meant to be testing around.
     */
    @Value("${fintrack.security.ratelimit.login.limit:10}")
    private int loginLimit;

    @Value("${fintrack.security.ratelimit.login.window:PT15M}")
    private Duration loginWindow;

    /** Account creation is rarer and more costly to abuse, so it is tighter. */
    @Value("${fintrack.security.ratelimit.register.limit:5}")
    private int registerLimit;

    @Value("${fintrack.security.ratelimit.register.window:PT1H}")
    private Duration registerWindow;

    private static final Set<String> LOGIN_PATHS =
            Set.of("/api/v1/auth/login", "/api/v1/auth/refresh");
    private static final String REGISTER_PATH = "/api/v1/auth/register";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(LOGIN_PATHS.contains(path) || REGISTER_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isRegister = REGISTER_PATH.equals(path);

        int limit = isRegister ? registerLimit : loginLimit;
        Duration window = isRegister ? registerWindow : loginWindow;

        // The bucket includes the path, so exhausting the register quota does not also lock
        // the caller out of signing in to an account they already have.
        String bucket = path + ":" + clientIp(request);
        RateLimiter.Decision decision = rateLimiter.check(bucket, limit, window);

        // Advertised on every response, so a well-behaved client can back off before it is
        // refused rather than discovering the limit by hitting it.
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            log.info("Rate limit exceeded for {}", bucket);
            writeTooManyRequests(response, path, decision.retryAfterSeconds());
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, String path, long retryAfter)
            throws IOException {
        response.setStatus(429);
        // The standard header for this, which HTTP clients and browsers already understand.
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiError body = ApiError.of(
                ErrorCode.TOO_MANY_REQUESTS,
                "Too many attempts. Please try again in %d seconds.".formatted(retryAfter),
                Map.of("retryAfterSeconds", retryAfter),
                path);

        objectMapper.writeValue(response.getOutputStream(), body.wrap());
    }

    /**
     * Behind a proxy the socket address is the proxy's, so the first X-Forwarded-For entry is
     * preferred. That header is client-controlled and trivially spoofed, which for a rate
     * limiter means an attacker can rotate it to get a fresh bucket - acceptable only because
     * a deployment terminates TLS at an ingress that overwrites it. It is never used for an
     * authorization decision.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
