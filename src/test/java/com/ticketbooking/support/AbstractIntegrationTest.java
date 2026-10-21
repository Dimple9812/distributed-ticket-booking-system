package com.ticketbooking.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbooking.config.BookingProperties;
import com.ticketbooking.event.EventCategory;
import com.ticketbooking.event.EventDtos.CreateEventRequest;
import com.ticketbooking.event.EventService;
import com.ticketbooking.screening.ScreeningDtos.AssignSeatsRequest;
import com.ticketbooking.screening.ScreeningDtos.CreateScreeningRequest;
import com.ticketbooking.screening.ScreeningService;
import com.ticketbooking.security.JwtService;
import com.ticketbooking.seat.SeatType;
import com.ticketbooking.user.Role;
import com.ticketbooking.user.User;
import com.ticketbooking.user.UserRepository;
import com.ticketbooking.venue.VenueDtos.CreateVenueRequest;
import com.ticketbooking.venue.VenueDtos.RowLayout;
import com.ticketbooking.venue.VenueService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Boots the full application on a random port against real PostgreSQL, Redis and
 * Kafka. All subclasses share one Spring context (identical configuration), so the
 * context starts once per test run. State is wiped before every test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestBeans.class)
public abstract class AbstractIntegrationTest {

    protected static final TestInfrastructure INFRA = TestInfrastructure.get();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        INFRA.register(registry);
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected VenueService venueService;
    @Autowired
    protected EventService eventService;
    @Autowired
    protected ScreeningService screeningService;

    @Autowired
    protected ControllableNotificationSender emailSender;
    @Autowired
    protected BookingProperties bookingProperties;

    protected ApiClient api;

    @BeforeEach
    void resetState() {
        clock.reset();
        jdbc.execute("""
                TRUNCATE notifications, processed_events, outbox_events, idempotency_records, payment_attempts,
                         booking_items, seat_hold_items, screening_seats, bookings, seat_holds, screenings,
                         events, seats, venues, users RESTART IDENTITY CASCADE
                """);
        try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        } catch (RuntimeException redisDownInThisTest) {
            // RedisUnavailableIT deliberately points at a dead Redis.
        }
        emailSender.reset();
        bookingProperties.getRedisLock().setEnabled(true);
        bookingProperties.getRedisLock().setFailureMode(BookingProperties.RedisFailureMode.DB_ONLY);
        api = new ApiClient("http://localhost:" + port, objectMapper);
    }

    // ------------------------------------------------------------------ fixtures

    public record Customer(Long id, String email, String token) {
    }

    public record ScreeningFixture(Long screeningId, Long venueId, List<Long> screeningSeatIds) {
    }

    protected Customer registerCustomer(String name) {
        String email = name.toLowerCase() + "-" + SEQUENCE.incrementAndGet() + "@example.com";
        ApiClient.Response response = api.post("/api/v1/auth/register", null,
                Map.of("email", email, "password", "Passw0rd!long", "fullName", name));
        if (response.status() != 201) {
            throw new IllegalStateException("registration failed: " + response);
        }
        return new Customer(response.body().path("user").path("id").asLong(), email,
                response.body().path("accessToken").asText());
    }

    protected String adminToken() {
        User admin = userRepository.save(new User("admin-" + SEQUENCE.incrementAndGet() + "@example.com",
                "{noop-not-used}", "Admin", Role.ADMIN, clock.instant()));
        return jwtService.issue(admin).token();
    }

    /** Creates a venue with one row of {@code seats} STANDARD seats and a screening tomorrow priced at 250.00. */
    protected ScreeningFixture screeningWithSeats(int seats) {
        return screeningWithSeats(seats, Duration.ofDays(1));
    }

    protected ScreeningFixture screeningWithSeats(int seats, Duration startsIn) {
        int n = SEQUENCE.incrementAndGet();
        var venue = venueService.create(new CreateVenueRequest("Test Venue " + n, "Kanpur", "Test address",
                List.of(new RowLayout("A", seats, SeatType.STANDARD))));
        var event = eventService.create(new CreateEventRequest("Test Event " + n, null, EventCategory.MOVIE, "English", 120));
        var screening = screeningService.create(new CreateScreeningRequest(event.id(), venue.id(),
                clock.instant().plus(startsIn)));
        screeningService.assignSeats(screening.id(),
                new AssignSeatsRequest(null, Map.of(SeatType.STANDARD, new BigDecimal("250.00"))));
        List<Long> screeningSeatIds = jdbc.queryForList(
                "select id from screening_seats where screening_id = ? order by id", Long.class, screening.id());
        return new ScreeningFixture(screening.id(), venue.id(), screeningSeatIds);
    }

    // ------------------------------------------------------------------ API shortcuts

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    protected ApiClient.Response hold(Customer customer, Long screeningId, List<Long> seatIds, String idempotencyKey) {
        return api.post("/api/v1/screenings/" + screeningId + "/holds", customer.token(),
                Map.of("screeningSeatIds", seatIds), Map.of("Idempotency-Key", idempotencyKey));
    }

    protected ApiClient.Response hold(Customer customer, Long screeningId, List<Long> seatIds) {
        return hold(customer, screeningId, seatIds, newKey());
    }

    protected ApiClient.Response confirm(Customer customer, String holdId, String idempotencyKey) {
        return api.post("/api/v1/bookings/confirm", customer.token(),
                Map.of("holdId", holdId, "paymentMethodToken", "pm_card_visa"), Map.of("Idempotency-Key", idempotencyKey));
    }

    protected ApiClient.Response confirm(Customer customer, String holdId) {
        return confirm(customer, holdId, newKey());
    }

    protected String seatStatus(Long screeningSeatId) {
        return jdbc.queryForObject("select status from screening_seats where id = ?", String.class, screeningSeatId);
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
