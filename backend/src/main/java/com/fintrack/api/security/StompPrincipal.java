package com.fintrack.api.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.UUID;

/**
 * The principal attached to an authenticated STOMP session.
 *
 * <h2>Why a dedicated type</h2>
 * Spring resolves {@code /user/**} destinations by {@link #getName()}. A plain
 * {@code UsernamePasswordAuthenticationToken} wrapping {@link AuthenticatedUser} reports the
 * <em>email</em> there, because that is what {@code UserDetails.getUsername()} returns - so
 * a message addressed by user id silently reaches nobody. The failure is quiet: the send
 * succeeds, the broker finds no matching session, and the message is dropped.
 * <p>
 * Overriding the name to the user id fixes the mismatch in the right direction. The id is
 * immutable, whereas an email can be changed - and a session keyed on a mutable value would
 * stop receiving its own messages the moment the user updated their address.
 */
public class StompPrincipal extends UsernamePasswordAuthenticationToken {

    private final transient UUID userId;

    public StompPrincipal(AuthenticatedUser user) {
        super(user, null, user.getAuthorities());
        this.userId = user.id();
    }

    /** The user id, which is what {@code convertAndSendToUser} addresses. */
    @Override
    public String getName() {
        return userId.toString();
    }

    public UUID userId() {
        return userId;
    }
}
