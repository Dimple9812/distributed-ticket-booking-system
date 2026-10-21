package com.ticketbooking.auth;

import com.ticketbooking.user.Role;
import com.ticketbooking.user.User;
import com.ticketbooking.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Creates the first ADMIN account from ADMIN_EMAIL / ADMIN_PASSWORD environment
 * variables when both are set. No credentials live in source code.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
                          @Value("${app.bootstrap.admin.email:}") String email,
                          @Value("${app.bootstrap.admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.info("ADMIN_EMAIL/ADMIN_PASSWORD not set; skipping admin bootstrap");
            return;
        }
        if (password.length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least 12 characters");
        }
        String normalized = AuthService.normalize(email);
        if (userRepository.existsByEmail(normalized)) {
            return;
        }
        try {
            userRepository.save(new User(normalized, passwordEncoder.encode(password), "Administrator", Role.ADMIN,
                    clock.instant()));
            log.info("Bootstrapped admin account {}", normalized);
        } catch (DataIntegrityViolationException alreadyCreatedByAnotherInstance) {
            log.info("Admin account {} was created concurrently by another instance", normalized);
        }
    }
}
