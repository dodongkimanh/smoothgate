package com.smitgate.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    // Retry up to 3 times with 3s / 5s backoff when the DB connection pool is temporarily
    // exhausted or Supabase is doing a cold-reconnect after an idle drop.
    // @Retryable MUST be placed ABOVE @Transactional so the retry proxy wraps the TX proxy:
    //   Retry(outer) → Transaction(inner) → method body
    // This way, if Transaction.doBegin() throws CannotCreateTransactionException, Retry
    // catches it OUTSIDE the transaction and opens a fresh transaction on the next attempt.
    @Retryable(
            retryFor = {
                CannotCreateTransactionException.class,
                DataAccessResourceFailureException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 3000, multiplier = 1.5)
    )
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

            return new org.springframework.security.core.userdetails.User(
                    user.getEmail(),
                    user.getPasswordHash(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
            );
        } catch (UsernameNotFoundException e) {
            throw e; // don't log as error — it’s a normal wrong-email response
        } catch (CannotCreateTransactionException | DataAccessResourceFailureException e) {
            // Let @Retryable handle transient connection-acquire failures.
            throw e;
        } catch (Exception e) {
            log.error("[Auth] Unexpected error loading user '{}': {}", email, e.getMessage());
            throw new InternalAuthenticationServiceException(
                    "Database error during authentication. Please try again.", e);
        }
    }
}
