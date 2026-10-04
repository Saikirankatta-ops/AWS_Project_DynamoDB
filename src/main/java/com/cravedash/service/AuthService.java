package com.cravedash.service;

import com.cravedash.model.AuthenticatedUser;
import com.cravedash.model.UserRole;
import com.cravedash.repository.AuthDynamoRepository;
import com.cravedash.repository.AuthDynamoRepository.AccountRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

@Service
public class AuthService {
    public static final String SESSION_COOKIE_NAME = "CRAVEDASH_SESSION";
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthDynamoRepository authRepository;
    private final PasswordEncoder passwordEncoder;
    private final String ownerUsername;
    private final String ownerPasswordHash;

    public AuthService(
            AuthDynamoRepository authRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.bootstrap-username:}") @NonNull String ownerUsername,
            @Value("${app.auth.bootstrap-password:}") @NonNull String ownerPassword) {
        this.authRepository = authRepository;
        this.passwordEncoder = passwordEncoder;
        this.ownerUsername = ownerUsername.trim();
        validatePasswordLength(ownerPassword);
        this.ownerPasswordHash = ownerPassword.isBlank()
                ? ""
                : passwordEncoder.encode(ownerPassword);
    }

    public AuthenticatedSession register(@NonNull String username, @NonNull String password) {
        validatePasswordLength(password);
        String normalizedUsername = username.trim();
        if (normalizedUsername.equalsIgnoreCase(ownerUsername) && !ownerUsername.isBlank()) {
            throw new UsernameAlreadyExistsException();
        }
        if (!authRepository.createAccount(
                normalizedUsername, UserRole.CUSTOMER.name(), passwordEncoder.encode(password))) {
            throw new UsernameAlreadyExistsException();
        }
        return createSession(new AuthenticatedUser(normalizedUsername, UserRole.CUSTOMER));
    }

    public AuthenticatedSession login(@NonNull String username, @NonNull String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidCredentialsException();
        }
        bootstrapOwner();
        String normalizedUsername = username.trim();
        AccountRecord account = authRepository.findAccount(normalizedUsername)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return createSession(new AuthenticatedUser(normalizedUsername, UserRole.valueOf(account.role())));
    }

    public AuthenticatedUser getSessionUser(@Nullable String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidCredentialsException();
        }
        String username = authRepository.findSessionUsername(token)
                .orElseThrow(InvalidCredentialsException::new);
        AccountRecord account = authRepository.findAccount(username)
                .orElseThrow(InvalidCredentialsException::new);
        return new AuthenticatedUser(username, UserRole.valueOf(account.role()));
    }

    public void logout(@Nullable String token) {
        if (token != null && !token.isBlank()) {
            authRepository.deleteSession(token);
        }
    }

    private void bootstrapOwner() {
        if (!ownerUsername.isBlank() && !ownerPasswordHash.isBlank()) {
            authRepository.createAccount(ownerUsername, UserRole.OWNER.name(), ownerPasswordHash);
        }
    }

    private AuthenticatedSession createSession(AuthenticatedUser user) {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        authRepository.createSession(token, user.username(), Instant.now().plus(SESSION_TTL));
        return new AuthenticatedSession(Objects.requireNonNull(token), user);
    }

    private void validatePasswordLength(String password) {
        if (!password.isBlank() && password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Passwords must be no longer than 72 UTF-8 bytes.");
        }
    }

    public record AuthenticatedSession(@NonNull String token, AuthenticatedUser user) {
    }
}
