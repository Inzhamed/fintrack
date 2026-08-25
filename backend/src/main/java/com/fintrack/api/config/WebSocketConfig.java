package com.fintrack.api.config;

import com.fintrack.api.security.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * STOMP over WebSocket, for pushing budget alerts as they happen.
 *
 * <h2>Why STOMP rather than a raw socket</h2>
 * A raw WebSocket is a byte pipe: subscriptions, addressing and framing all have to be
 * invented. STOMP supplies them, and - the reason that matters here - it carries headers on
 * its CONNECT frame. A browser cannot set an Authorization header on the WebSocket handshake
 * itself, so without STOMP the token would have to travel as a query parameter, where it
 * lands in access logs and proxy history.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;

    @Value("${fintrack.security.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // The handshake is not same-origin in development, where Vite serves on
                // another port. Listed explicitly rather than "*", which the spec forbids
                // alongside credentials anyway.
                .setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // An in-memory broker. Sufficient while the API is a single instance; a second
        // replica would need an external relay, since a client connected to instance A
        // cannot be reached by a message published on instance B. That is a Phase 4 problem,
        // when there is more than one pod.
        registry.enableSimpleBroker("/topic", "/queue");

        // Prefix for anything a client sends *to* the server. Nothing does yet - this is a
        // one-way push channel - but the prefix has to exist for the broker to route.
        registry.setApplicationDestinationPrefixes("/app");

        // Enables convertAndSendToUser, which resolves /user/** against the Principal that
        // the interceptor attached at CONNECT.
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Authenticates the CONNECT frame. Without this every subscription would be
        // anonymous, and "send to this user" would have no user to resolve.
        registration.interceptors(authInterceptor);
    }
}
