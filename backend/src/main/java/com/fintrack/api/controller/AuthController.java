package com.fintrack.api.controller;

import com.fintrack.api.dto.auth.*;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints.
 * <p>
 * Controllers here do HTTP only: bind and validate the request, hand it to the service,
 * map the result to a status code. No business rules, no repository access.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, sign-in and token lifecycle")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account and sign in")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "Email already registered", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public TokenResponse register(@Valid @RequestBody RegisterRequest request,
                                  HttpServletRequest servletRequest) {
        return authService.register(request, userAgent(servletRequest), clientIp(servletRequest));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a token pair")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest servletRequest) {
        return authService.login(request, userAgent(servletRequest), clientIp(servletRequest));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new pair",
            description = "The supplied refresh token is consumed and replaced. Presenting a "
                    + "token twice revokes every session for that user.")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request,
                                 HttpServletRequest servletRequest) {
        return authService.refresh(request.refreshToken(),
                userAgent(servletRequest), clientIp(servletRequest));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out of this session",
            description = "Revokes the supplied refresh token. The access token remains valid "
                    + "until it expires, which is why its lifetime is short.")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out of every session on every device")
    public void logoutEverywhere(@AuthenticationPrincipal AuthenticatedUser principal) {
        authService.logoutEverywhere(principal.id());
    }

    @GetMapping("/me")
    @Operation(summary = "The currently authenticated account")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(authService.currentUser(principal.id()));
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }

    /**
     * Behind an ingress or load balancer the socket address is the proxy's, so the first
     * entry of X-Forwarded-For is preferred when present. This is advisory data used for
     * session bookkeeping only - the header is client-controlled and is never trusted for
     * an authorization decision.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
