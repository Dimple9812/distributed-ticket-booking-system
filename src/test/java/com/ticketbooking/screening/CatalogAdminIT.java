package com.ticketbooking.screening;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ApiClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogAdminIT extends AbstractIntegrationTest {

    @Test
    void adminBuildsCatalogAndCustomersBrowseIt() {
        String admin = adminToken();

        ApiClient.Response venue = api.post("/api/v1/admin/venues", admin, Map.of(
                "name", "Audi 7", "city", "Kanpur", "address", "Mall Road",
                "rows", List.of(Map.of("rowLabel", "A", "seatCount", 4, "seatType", "STANDARD"),
                        Map.of("rowLabel", "B", "seatCount", 2, "seatType", "VIP"))));
        assertThat(venue.status()).isEqualTo(201);
        assertThat(venue.body().path("seatCount").asInt()).isEqualTo(6);
        long venueId = venue.body().path("id").asLong();

        ApiClient.Response event = api.post("/api/v1/admin/events", admin, Map.of(
                "title", "Dune Part Two", "category", "MOVIE", "language", "English", "durationMinutes", 166));
        assertThat(event.status()).isEqualTo(201);
        long eventId = event.body().path("id").asLong();

        Instant startsAt = clock.instant().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MINUTES);
        ApiClient.Response screening = api.post("/api/v1/admin/screenings", admin,
                Map.of("eventId", eventId, "venueId", venueId, "startsAt", startsAt.toString()));
        assertThat(screening.status()).isEqualTo(201);
        long screeningId = screening.body().path("id").asLong();
        assertThat(Instant.parse(screening.body().path("endsAt").asText())).isEqualTo(startsAt.plus(Duration.ofMinutes(166)));

        ApiClient.Response assign = api.post("/api/v1/admin/screenings/" + screeningId + "/seats", admin,
                Map.of("priceBySeatType", Map.of("STANDARD", 300, "VIP", 900)));
        assertThat(assign.status()).isEqualTo(201);
        assertThat(assign.body().path("assignedSeats").asInt()).isEqualTo(6);

        // Assigning the same seats twice is rejected.
        ApiClient.Response again = api.post("/api/v1/admin/screenings/" + screeningId + "/seats", admin,
                Map.of("priceBySeatType", Map.of("STANDARD", 300, "VIP", 900)));
        assertThat(again.status()).isEqualTo(409);

        // Public browsing without a token.
        assertThat(api.get("/api/v1/events", null).body().path("totalElements").asInt()).isEqualTo(1);
        JsonNode detail = api.get("/api/v1/events/" + eventId, null).body();
        assertThat(detail.path("upcomingScreenings").size()).isEqualTo(1);
        JsonNode summary = api.get("/api/v1/screenings/" + screeningId, null).body();
        assertThat(summary.path("totalSeats").asInt()).isEqualTo(6);
        assertThat(summary.path("availableSeats").asInt()).isEqualTo(6);
        JsonNode seatMap = api.get("/api/v1/screenings/" + screeningId + "/seats", null).body();
        assertThat(seatMap.path("seats").size()).isEqualTo(6);
        assertThat(seatMap.path("summary").path("AVAILABLE").asInt()).isEqualTo(6);
        assertThat(seatMap.path("seats").get(5).path("price").decimalValue()).isEqualByComparingTo("900");
    }

    @Test
    void overlappingScreeningInSameVenueIsRejected() {
        String admin = adminToken();
        ScreeningFixture fixture = screeningWithSeats(2);
        Long eventId = jdbc.queryForObject("select event_id from screenings where id = ?", Long.class, fixture.screeningId());
        Instant existingStart = jdbc.queryForObject("select starts_at from screenings where id = ?",
                java.sql.Timestamp.class, fixture.screeningId()).toInstant();

        ApiClient.Response overlap = api.post("/api/v1/admin/screenings", admin, Map.of("eventId", eventId,
                "venueId", fixture.venueId(), "startsAt", existingStart.plus(Duration.ofMinutes(30)).toString()));
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.code()).isEqualTo("SCREENING_OVERLAP");
    }

    @Test
    void screeningValidation() {
        String admin = adminToken();
        ApiClient.Response past = api.post("/api/v1/admin/screenings", admin,
                Map.of("eventId", 1, "venueId", 1, "startsAt", "2020-01-01T10:00:00Z"));
        assertThat(past.status()).isEqualTo(400);

        ApiClient.Response unknownEvent = api.post("/api/v1/admin/screenings", admin, Map.of("eventId", 999,
                "venueId", 999, "startsAt", clock.instant().plus(Duration.ofDays(1)).toString()));
        assertThat(unknownEvent.status()).isEqualTo(404);
        assertThat(unknownEvent.code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void seatsFromAnotherVenueCannotBeAssigned() {
        String admin = adminToken();
        ScreeningFixture a = screeningWithSeats(2);
        ScreeningFixture b = screeningWithSeats(2);
        Long foreignSeatId = jdbc.queryForObject("select id from seats where venue_id = ? limit 1", Long.class, b.venueId());
        ApiClient.Response response = api.post("/api/v1/admin/screenings/" + a.screeningId() + "/seats", admin,
                Map.of("seatIds", List.of(foreignSeatId), "priceBySeatType", Map.of("STANDARD", 100)));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("INVALID_SEAT_SELECTION");
    }

    @Test
    void customersCannotCallAdminEndpoints() {
        Customer customer = registerCustomer("Neha");
        ApiClient.Response venue = api.post("/api/v1/admin/venues", customer.token(), Map.of(
                "name", "X", "city", "Y", "address", "Z", "rows", List.of(Map.of("rowLabel", "A", "seatCount", 1, "seatType", "VIP"))));
        assertThat(venue.status()).isEqualTo(403);
        assertThat(venue.code()).isEqualTo("ACCESS_DENIED");
        assertThat(api.post("/api/v1/admin/events", customer.token(), Map.of("title", "x", "category", "MOVIE",
                "durationMinutes", 10)).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/admin/screenings", customer.token(), Map.of()).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/admin/screenings/1/seats", customer.token(), Map.of()).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/admin/events", null, Map.of()).status()).isEqualTo(401);
        assertThat(count("select count(*) from venues")).isZero();
    }

    @Test
    void unknownScreeningReturns404() {
        assertThat(api.get("/api/v1/screenings/424242", null).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/screenings/424242/seats", null).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/screenings/abc", null).status()).isEqualTo(400);
    }
}
