package com.ticketbooking.config;

import com.ticketbooking.event.EventCategory;
import com.ticketbooking.event.EventDtos.CreateEventRequest;
import com.ticketbooking.event.EventDtos.EventResponse;
import com.ticketbooking.event.EventService;
import com.ticketbooking.screening.ScreeningDtos.AssignSeatsRequest;
import com.ticketbooking.screening.ScreeningDtos.CreateScreeningRequest;
import com.ticketbooking.screening.ScreeningDtos.ScreeningResponse;
import com.ticketbooking.screening.ScreeningService;
import com.ticketbooking.seat.SeatType;
import com.ticketbooking.venue.VenueDtos.CreateVenueRequest;
import com.ticketbooking.venue.VenueDtos.RowLayout;
import com.ticketbooking.venue.VenueDtos.VenueResponse;
import com.ticketbooking.venue.VenueRepository;
import com.ticketbooking.venue.VenueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Development-only sample catalog (enabled by app.seed.enabled / the "local" profile).
 * Runs once: if any venue already exists it does nothing. It goes through the same
 * services as the admin API, so seeded data obeys every validation rule.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final VenueRepository venueRepository;
    private final VenueService venueService;
    private final EventService eventService;
    private final ScreeningService screeningService;
    private final Clock clock;

    public DevDataSeeder(VenueRepository venueRepository, VenueService venueService, EventService eventService,
                         ScreeningService screeningService, Clock clock) {
        this.venueRepository = venueRepository;
        this.venueService = venueService;
        this.eventService = eventService;
        this.screeningService = screeningService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (venueRepository.count() > 0) {
            log.info("Seed data already present; skipping");
            return;
        }
        VenueResponse cinema = venueService.create(new CreateVenueRequest("Grand Cinema Audi 1", "Kanpur",
                "The Mall, Kanpur 208001", List.of(
                new RowLayout("A", 10, SeatType.STANDARD), new RowLayout("B", 10, SeatType.STANDARD),
                new RowLayout("C", 10, SeatType.STANDARD), new RowLayout("D", 10, SeatType.PREMIUM),
                new RowLayout("E", 10, SeatType.PREMIUM), new RowLayout("F", 6, SeatType.VIP))));
        VenueResponse hall = venueService.create(new CreateVenueRequest("Riverside Concert Hall", "Lucknow",
                "Gomti Nagar, Lucknow 226010", List.of(
                new RowLayout("A", 20, SeatType.VIP), new RowLayout("B", 20, SeatType.PREMIUM),
                new RowLayout("C", 20, SeatType.STANDARD), new RowLayout("D", 20, SeatType.STANDARD))));

        EventResponse movie = eventService.create(new CreateEventRequest("Interstellar (Re-release)",
                "A team of explorers travels through a wormhole in search of a new home for humanity.",
                EventCategory.MOVIE, "English", 169));
        EventResponse concert = eventService.create(new CreateEventRequest("Indie Nights Live",
                "An evening with independent artists from across India.", EventCategory.CONCERT, "Hindi", 150));

        Instant tomorrow = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1));
        Map<SeatType, BigDecimal> cinemaPrices = Map.of(SeatType.STANDARD, new BigDecimal("250.00"),
                SeatType.PREMIUM, new BigDecimal("400.00"), SeatType.VIP, new BigDecimal("750.00"));
        Map<SeatType, BigDecimal> concertPrices = Map.of(SeatType.STANDARD, new BigDecimal("999.00"),
                SeatType.PREMIUM, new BigDecimal("1999.00"), SeatType.VIP, new BigDecimal("4999.00"));

        for (Instant start : List.of(tomorrow.plus(Duration.ofHours(8)), tomorrow.plus(Duration.ofHours(12)))) {
            ScreeningResponse s = screeningService.create(new CreateScreeningRequest(movie.id(), cinema.id(), start));
            screeningService.assignSeats(s.id(), new AssignSeatsRequest(null, cinemaPrices));
        }
        ScreeningResponse gig = screeningService.create(new CreateScreeningRequest(concert.id(), hall.id(),
                tomorrow.plus(Duration.ofHours(13))));
        screeningService.assignSeats(gig.id(), new AssignSeatsRequest(null, concertPrices));
        log.info("Seeded sample catalog: 2 venues, 2 events, 3 screenings");
    }
}
