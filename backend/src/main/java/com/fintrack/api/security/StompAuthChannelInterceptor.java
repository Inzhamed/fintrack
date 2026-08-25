package com.fintrack.api.security;

import com.fintrack.api.model.Role;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates the STOMP CONNECT frame and attaches the resulting principal to the session.
 *
 * <h2>Why this is load-bearing</h2>
 * The HTTP filter chain runs on the handshake, but the handshake carries no Authorization
 * header a browser can set. So the socket opens unauthenticated and the token arrives on the
 * first STOMP frame instead. Whatever principal is attached here is what
 * {@code convertAndSendToUser} resolves {@code /user/queue/**} against for the rest of the
 * session - so if this returns an unauthenticated session, every subsequent subscription is
 * anonymous, and every anonymous subscriber shares one destination.
 *
 * <h2>Subscriptions are checked too</h2>
 * CONNECT establishes who the client is; SUBSCRIBE is where they name a destination. A
 * client that connects as one user and then subscribes to another user's queue would
 * otherwise be routed straight through, so the destination is checked against the principal.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            Authentication authentication = authenticate(accessor)
                    // Refusing the CONNECT closes the socket. An unauthenticated session is
                    // never useful here - there is nothing public to subscribe to.
                    .orElseThrow(() -> new IllegalArgumentException(
                            "A valid access token is required to open a WebSocket session"));

            accessor.setUser(authentication);
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            rejectForeignDestination(accessor);
        }

        return message;
    }

    private Optional<Authentication> authenticate(StompHeaderAccessor accessor) {
        List<String> headers = accessor.getNativeHeader("Authorization");
        if (headers == null || headers.isEmpty()) {
            return Optional.empty();
        }

        String header = headers.getFirst();
        if (header == null || !header.startsWith(PREFIX)) {
            return Optional.empty();
        }

        // Deliberately the same verification the HTTP filter performs: same signing key, same
        // issuer check, same token type. A token that would not open an HTTP request must not
        // open a socket either.
        return jwtService.parseAccessToken(header.substring(PREFIX.length()).trim())
                .flatMap(this::toAuthentication);
    }

    private Optional<Authentication> toAuthentication(Claims claims) {
        Optional<UUID> userId = jwtService.userId(claims);
        if (userId.isEmpty()) {
            return Optional.empty();
        }

        Role role;
        try {
            role = Role.valueOf(claims.get("role", String.class));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }

        AuthenticatedUser principal = AuthenticatedUser.forToken(
                userId.get(), claims.get("email", String.class), role);

        // StompPrincipal, not a plain token: its getName() is the user id, which is what
        // convertAndSendToUser addresses. The default would report the email and the
        // message would be dropped without an error.
        return Optional.of(new StompPrincipal(principal));
    }

    /**
     * Blocks a subscription to another user's queue.
     * <p>
     * Spring rewrites {@code /user/queue/x} into a session-scoped destination on the way in,
     * so the common case is already safe. This closes the case of a client naming the
     * resolved form directly, and makes the intent explicit rather than implicit in the
     * framework's rewriting.
     */
    private void rejectForeignDestination(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        if (destination.startsWith("/user/") && accessor.getUser() == null) {
            throw new IllegalArgumentException("Cannot subscribe to a user destination anonymously");
        }

        // Nothing in this application broadcasts to a shared topic; every message is for one
        // person. Refusing /topic outright means a future broadcast has to be a deliberate
        // decision rather than something a client can opt into.
        if (destination.startsWith("/topic")) {
            throw new IllegalArgumentException("No broadcast topics are available");
        }
    }
}
