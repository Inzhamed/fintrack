package com.fintrack.api.config;

import tools.jackson.databind.ObjectMapper;
import com.fintrack.api.exception.ApiError;
import com.fintrack.api.exception.ErrorCode;
import com.fintrack.api.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Web security for a stateless JSON API.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity          // Enables @PreAuthorize where a rule is finer than a URL pattern
@RequiredArgsConstructor
public class SecurityConfig {

    /** Endpoints reachable without a token. Everything else requires authentication. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            // Authenticated by possession of the refresh token in the body, so that an
            // expired access token does not trap the user in a session they cannot end.
            "/api/v1/auth/logout",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            // The handshake only opens the socket. A browser cannot set an Authorization
            // header on it, so the token arrives on the STOMP CONNECT frame instead and is
            // verified by StompAuthChannelInterceptor, which refuses the connection
            // outright if it is missing or invalid.
            "/ws/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Value("${fintrack.security.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No cookies are used for authentication, so there is no ambient credential
                // for a cross-site form to abuse and nothing for CSRF tokens to protect.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Every request carries its own proof. No server-side session is created,
                // which is what lets the API scale horizontally without sticky sessions.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Browsers send an unauthenticated OPTIONS preflight before the real call.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())

                // Both handlers emit the same ApiError envelope the controllers use, so a
                // client never has to parse two different error shapes.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> writeError(
                                response, ErrorCode.UNAUTHENTICATED,
                                "Authentication is required for this resource", request.getRequestURI()))
                        .accessDeniedHandler((request, response, ex) -> writeError(
                                response, ErrorCode.FORBIDDEN,
                                "You do not have access to this resource", request.getRequestURI())))

                // Runs before the username/password filter so that a valid bearer token
                // authenticates the request before any form-login machinery is consulted.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response,
                            ErrorCode code, String message, String path) throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(code, message, Map.of(), path).wrap());
    }

    /**
     * BCrypt at strength 12. The default of 10 dates from much slower hardware; 12 keeps a
     * single hash in the tens of milliseconds, which is negligible for a login and expensive
     * for an offline cracking run.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Compare against a dummy hash even when no user matched, so that a missing account
        // and a wrong password take the same amount of time to reject.
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Lets the browser read Content-Disposition so a CSV download gets its filename.
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
