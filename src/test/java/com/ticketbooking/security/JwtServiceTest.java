package com.ticketbooking.security;

import com.ticketbooking.user.Role;
import com.ticketbooking.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    private final JwtProperties properties = new JwtProperties(SECRET, "ticket-booking-system", Duration.ofHours(1));

    @Test
    void issuedTokenVerifiesToTheSameUserAndRole() {
        JwtService service = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        JwtService.IssuedToken token = service.issue(user(42L, Role.ADMIN));

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(service.verify(token.token()))
                .contains(new AuthenticatedUser(42L, "user@example.com", Role.ADMIN));
    }

    @Test
    void expiredTamperedOrForeignTokensAreRejected() {
        JwtService issuer = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = issuer.issue(user(1L, Role.CUSTOMER)).token();

        JwtService later = new JwtService(properties, Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        assertThat(later.verify(token)).isEmpty();

        String[] parts = token.split("\\.");
        String tamperedPayload = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "xx." + parts[2];
        assertThat(issuer.verify(tamperedPayload)).isEmpty();

        JwtService otherKey = new JwtService(new JwtProperties("another-secret-another-secret-0123456789",
                "ticket-booking-system", Duration.ofHours(1)), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(otherKey.verify(token)).isEmpty();
        assertThat(issuer.verify("not-a-jwt")).isEmpty();
    }

    private static User user(Long id, Role role) {
        User user = new User("user@example.com", "hash", "User", role, NOW);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
