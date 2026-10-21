package com.ticketbooking.auth;

import com.ticketbooking.auth.AuthDtos.AuthResponse;
import com.ticketbooking.auth.AuthDtos.LoginRequest;
import com.ticketbooking.auth.AuthDtos.RegisterRequest;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import com.ticketbooking.security.JwtService;
import com.ticketbooking.user.Role;
import com.ticketbooking.user.User;
import com.ticketbooking.user.UserRepository;
import com.ticketbooking.user.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Clock clock;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
    }

    /** Self-registration always creates a CUSTOMER; admins are provisioned out of band. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "An account with this email already exists");
        }
        try {
            User user = userRepository.saveAndFlush(new User(email, passwordEncoder.encode(request.password()),
                    request.fullName().trim(), Role.CUSTOMER, clock.instant()));
            return toResponse(user);
        } catch (DataIntegrityViolationException race) {
            // Two concurrent registrations for the same email: the unique constraint decides.
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "An account with this email already exists");
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email()))
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                // Same message for unknown email and wrong password: no account enumeration.
                .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_FAILED, "Invalid email or password"));
        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        JwtService.IssuedToken token = jwtService.issue(user);
        return new AuthResponse(token.token(), "Bearer", token.expiresAt(), UserResponse.from(user));
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
