package com.fintrack.api.security;

import com.fintrack.api.model.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads {@code Authorization: Bearer <jwt>} and, if the token verifies, populates the
 * security context for the duration of the request.
 * <p>
 * The filter never rejects anything itself. A missing or bad token simply leaves the request
 * anonymous, and the authorization rules in {@link com.fintrack.api.config.SecurityConfig}
 * decide whether that is acceptable for the endpoint being called. Keeping the two concerns
 * apart is what lets the same filter run in front of both public and protected routes.
 * <p>
 * Extends {@link OncePerRequestFilter} because a servlet filter otherwise runs again on
 * every {@code FORWARD} and {@code ERROR} dispatch - which would re-parse the token when the
 * error handler renders a response.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        // An existing authentication means another mechanism already ran. Do not overwrite it.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            bearerToken(request)
                    .flatMap(jwtService::parseAccessToken)
                    .ifPresent(claims -> authenticate(claims, request));
        }
        chain.doFilter(request, response);
    }

    private Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /**
     * Builds the principal from the token's claims alone - no database round trip. The
     * trade-off is that a role change or a deletion only takes effect once the current
     * access token expires, which is what the short TTL is for.
     */
    private void authenticate(Claims claims, HttpServletRequest request) {
        Optional<UUID> userId = jwtService.userId(claims);
        if (userId.isEmpty()) {
            return;
        }

        Role role;
        try {
            role = Role.valueOf(claims.get("role", String.class));
        } catch (IllegalArgumentException | NullPointerException ex) {
            // A token whose role no longer exists in this build: treat it as unauthenticated
            // rather than silently granting a default.
            log.debug("Rejecting token with unknown role claim for user {}", userId.get());
            return;
        }

        AuthenticatedUser principal = AuthenticatedUser.forToken(
                userId.get(), claims.get("email", String.class), role);

        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
