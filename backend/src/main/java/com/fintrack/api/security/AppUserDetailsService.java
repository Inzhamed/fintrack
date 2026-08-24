package com.fintrack.api.security;

import com.fintrack.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a user by email for the password-login flow.
 * <p>
 * Only used at login. Authenticated requests are resolved by
 * {@link JwtAuthenticationFilter} straight from the token's claims, so the happy path costs
 * no database read at all.
 */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(email)
                .map(AuthenticatedUser::forLogin)
                // The message is never shown to the caller - GlobalExceptionHandler answers
                // every AuthenticationException with the same "Invalid email or password", so
                // that a wrong address and a wrong password are indistinguishable.
                .orElseThrow(() -> new UsernameNotFoundException("No user for email " + email));
    }
}
