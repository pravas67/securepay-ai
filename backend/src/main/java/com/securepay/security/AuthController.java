package com.securepay.security;

import com.securepay.audit.AuditOutcome;
import com.securepay.audit.AuditService;
import com.securepay.common.RateLimiter;
import com.securepay.common.TooManyRequestsException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    /** After this many failed logins for one username in 15 minutes, further attempts are refused. */
    static final int MAX_FAILED_LOGINS = 5;

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final AuditService audit;
    private final RateLimiter failedLogins = new RateLimiter(MAX_FAILED_LOGINS, Duration.ofMinutes(15));

    public AuthController(AuthenticationManager authenticationManager, TokenService tokenService, AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.audit = audit;
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        String key = request.username().toLowerCase(Locale.ROOT);
        if (failedLogins.isBlocked(key)) {
            throw new TooManyRequestsException("Too many failed sign-in attempts. Try again in 15 minutes.");
        }
        try {
            var auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
            SecurityUser user = (SecurityUser) auth.getPrincipal();
            failedLogins.reset(key);
            TokenService.IssuedToken token = tokenService.issue(user);
            audit.record(user.id(), "AUTH_LOGIN", null, AuditOutcome.SUCCESS);
            return ResponseEntity.ok(new LoginResponse(token.token(), token.expiresAt(),
                    new LoginResponse.User(user.id(), user.username(), user.role())));
        } catch (AuthenticationException e) {
            failedLogins.tryAcquire(key);
            // No user id: the account may not exist. Same message either way, so usernames can't be enumerated.
            audit.record(null, "AUTH_LOGIN", "username=" + request.username(), AuditOutcome.DENIED);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Wrong username or password."));
        }
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, User user) {
        public record User(Long id, String username, String role) {
        }
    }
}
