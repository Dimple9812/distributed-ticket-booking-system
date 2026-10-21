package com.ticketbooking.config;

import com.ticketbooking.event.Event;
import com.ticketbooking.event.EventCategory;
import com.ticketbooking.event.EventDtos.CreateEventRequest;
import com.ticketbooking.event.EventRepository;
import com.ticketbooking.event.EventService;
import com.ticketbooking.exception.ApiException;
import com.ticketbooking.screening.ScreeningDtos.AssignSeatsRequest;
import com.ticketbooking.screening.ScreeningDtos.CreateScreeningRequest;
import com.ticketbooking.screening.ScreeningDtos.ScreeningResponse;
import com.ticketbooking.screening.ScreeningRepository;
import com.ticketbooking.screening.ScreeningService;
import com.ticketbooking.seat.SeatType;
import com.ticketbooking.venue.Venue;
import com.ticketbooking.venue.VenueDtos.CreateVenueRequest;
import com.ticketbooking.venue.VenueDtos.RowLayout;
import com.ticketbooking.venue.VenueRepository;
import com.ticketbooking.venue.VenueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Demo catalog (enabled by app.seed.enabled / the "local" profile).
 *
 * <p>Creates the sample venues and events once, then keeps a rolling window of screenings for
 * the next {@value #DAYS_AHEAD} days, so a long-running demo never runs out of upcoming shows.
 * The top-up runs at startup and daily. It goes through the same services as the admin API, so
 * seeded data obeys every validation rule; already-existing slots are skipped (and the database's
 * no-overlap constraint makes concurrent seeding by two instances harmless).
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    static final int DAYS_AHEAD = 7;

    private static final String CINEMA = "Grand Cinema Audi 1";
    private static final String HALL = "Riverside Concert Hall";
    private static final String STAGE = "Little Theatre Studio";

    /** event title, venue name, start offsets from midnight UTC (hours), prices */
    private record Slot(String eventTitle, String venueName, List<Integer> utcHours, Map<SeatType, BigDecimal> prices) {
    }

    private static final Map<SeatType, BigDecimal> CINEMA_PRICES = Map.of(SeatType.STANDARD, new BigDecimal("250.00"),
            SeatType.PREMIUM, new BigDecimal("400.00"), SeatType.VIP, new BigDecimal("750.00"));
    private static final Map<SeatType, BigDecimal> CONCERT_PRICES = Map.of(SeatType.STANDARD, new BigDecimal("999.00"),
            SeatType.PREMIUM, new BigDecimal("1999.00"), SeatType.VIP, new BigDecimal("4999.00"));
    private static final Map<SeatType, BigDecimal> THEATRE_PRICES = Map.of(SeatType.STANDARD, new BigDecimal("450.00"),
            SeatType.PREMIUM, new BigDecimal("650.00"));

    // Hours are UTC; 4:30, 8:30, 13:30 UTC = 10:00, 14:00, 19:00 IST.
    private static final List<Slot> SLOTS = List.of(
            new Slot("Interstellar (Re-release)", CINEMA, List.of(4, 8), CINEMA_PRICES),
            new Slot("The Grand Budapest Hotel", CINEMA, List.of(13), CINEMA_PRICES),
            new Slot("Indie Nights Live", HALL, List.of(13), CONCERT_PRICES),
            new Slot("Stand-up Saturday", STAGE, List.of(14), THEATRE_PRICES));

    private final VenueRepository venueRepository;
    private final EventRepository eventRepository;
    private final ScreeningRepository screeningRepository;
    private final VenueService venueService;
    private final EventService eventService;
    private final ScreeningService screeningService;
    private final Clock clock;

    public DevDataSeeder(VenueRepository venueRepository, EventRepository eventRepository,
                         ScreeningRepository screeningRepository, VenueService venueService, EventService eventService,
                         ScreeningService screeningService, Clock clock) {
        this.venueRepository = venueRepository;
        this.eventRepository = eventRepository;
        this.screeningRepository = screeningRepository;
        this.venueService = venueService;
        this.eventService = eventService;
        this.screeningService = screeningService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureCatalog();
        topUpScreenings();
    }

    @Scheduled(cron = "0 15 0 * * *", zone = "UTC")
    public void dailyTopUp() {
        topUpScreenings();
    }

    private void ensureCatalog() {
        venue(CINEMA, "Kanpur", "The Mall, Kanpur 208001", List.of(
                new RowLayout("A", 12, SeatType.STANDARD), new RowLayout("B", 12, SeatType.STANDARD),
                new RowLayout("C", 12, SeatType.STANDARD), new RowLayout("D", 12, SeatType.PREMIUM),
                new RowLayout("E", 12, SeatType.PREMIUM), new RowLayout("F", 8, SeatType.VIP)));
        venue(HALL, "Lucknow", "Gomti Nagar, Lucknow 226010", List.of(
                new RowLayout("A", 16, SeatType.VIP), new RowLayout("B", 18, SeatType.PREMIUM),
                new RowLayout("C", 20, SeatType.STANDARD), new RowLayout("D", 20, SeatType.STANDARD)));
        venue(STAGE, "Kanpur", "Civil Lines, Kanpur 208001", List.of(
                new RowLayout("A", 8, SeatType.PREMIUM), new RowLayout("B", 10, SeatType.STANDARD),
                new RowLayout("C", 10, SeatType.STANDARD)));

        event("Interstellar (Re-release)", "A team of explorers travels through a wormhole in search of a new home for humanity.",
                EventCategory.MOVIE, "English", 169);
        event("The Grand Budapest Hotel", "A legendary concierge and his lobby boy are framed for murder in a fictional alpine republic.",
                EventCategory.MOVIE, "English", 100);
        event("Indie Nights Live", "An evening with independent artists from across India.", EventCategory.CONCERT, "Hindi", 150);
        event("Stand-up Saturday", "Five comics, one mic, ninety minutes of new material.", EventCategory.COMEDY, "Hinglish", 90);
    }

    private void venue(String name, String city, String address, List<RowLayout> rows) {
        if (!venueRepository.existsByNameAndCity(name, city)) {
            venueService.create(new CreateVenueRequest(name, city, address, rows));
        }
    }

    private void event(String title, String description, EventCategory category, String language, int minutes) {
        if (eventRepository.findFirstByTitleOrderByIdAsc(title).isEmpty()) {
            eventService.create(new CreateEventRequest(title, description, category, language, minutes));
        }
    }

    void topUpScreenings() {
        Instant today = clock.instant().truncatedTo(ChronoUnit.DAYS);
        Instant earliest = clock.instant().plus(Duration.ofHours(3));   // leave time to actually book
        int created = 0;
        for (Slot slot : SLOTS) {
            Event event = eventRepository.findFirstByTitleOrderByIdAsc(slot.eventTitle()).orElse(null);
            Venue venue = venueRepository.findAll().stream().filter(v -> v.getName().equals(slot.venueName())).findFirst().orElse(null);
            if (event == null || venue == null) {
                continue;
            }
            for (int day = 0; day <= DAYS_AHEAD; day++) {
                for (int hour : slot.utcHours()) {
                    Instant start = today.plus(Duration.ofDays(day)).plus(Duration.ofHours(hour)).plus(Duration.ofMinutes(30));
                    Instant end = start.plus(Duration.ofMinutes(event.getDurationMinutes()));
                    if (start.isBefore(earliest) || screeningRepository.existsOverlapping(venue.getId(), start, end)) {
                        continue;
                    }
                    try {
                        ScreeningResponse s = screeningService.create(new CreateScreeningRequest(event.getId(), venue.getId(), start));
                        screeningService.assignSeats(s.id(), new AssignSeatsRequest(null, slot.prices()));
                        created++;
                    } catch (ApiException | DataIntegrityViolationException alreadyThere) {
                        log.debug("Skipping seed slot {} at {}: {}", slot.eventTitle(), start, alreadyThere.getMessage());
                    }
                }
            }
        }
        if (created > 0) {
            log.info("Seeded {} upcoming screening(s) for the next {} days", created, DAYS_AHEAD);
        }
    }
}
