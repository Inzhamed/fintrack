package com.fintrack.api.security;

import com.fintrack.api.model.Role;
import com.fintrack.api.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The principal stored in the security context.
 * <p>
 * Deliberately a small immutable value rather than the {@link User} entity. Parking a JPA
 * entity in the security context means holding a detached instance for the life of the
 * request - one whose lazy associations will throw the moment anything touches them, and
 * whose stale field values can be written back by accident.
 *
 * @param id           the user id, the only thing most code actually needs
 * @param email        used for logging and for the {@code /me} endpoint
 * @param passwordHash present only during authentication; never populated from a JWT
 */
public record AuthenticatedUser(
        UUID id,
        String email,
        String passwordHash,
        Role role,
        boolean enabled
) implements UserDetails {

    /** Built during password login, where the hash is needed for comparison. */
    public static AuthenticatedUser forLogin(User user) {
        return new AuthenticatedUser(
                user.getId(), user.getEmail(), user.getPasswordHash(), user.getRole(), true);
    }

    /**
     * Built from verified JWT claims. There is no password hash here: the request already
     * proved possession of a signed token, so nothing downstream needs the credential, and
     * omitting it keeps it out of memory entirely.
     */
    public static AuthenticatedUser forToken(UUID id, String email, Role role) {
        return new AuthenticatedUser(id, email, null, role, true);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Spring Security's notion of "username" is this application's email address. */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return enabled;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
