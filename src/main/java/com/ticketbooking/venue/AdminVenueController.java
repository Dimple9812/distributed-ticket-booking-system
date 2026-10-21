package com.ticketbooking.venue;

import com.ticketbooking.venue.VenueDtos.CreateVenueRequest;
import com.ticketbooking.venue.VenueDtos.VenueResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/venues")
@Tag(name = "Admin")
@SecurityRequirement(name = "bearerAuth")
public class AdminVenueController {

    private final VenueService venueService;

    public AdminVenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a venue and its physical seat layout (ADMIN)")
    public VenueResponse create(@Valid @RequestBody CreateVenueRequest request) {
        return venueService.create(request);
    }

    @GetMapping
    @Operation(summary = "List venues with seat counts (ADMIN)")
    public java.util.List<VenueResponse> list() {
        return venueService.list();
    }

    @GetMapping("/{venueId}")
    @Operation(summary = "Get a venue with its seats (ADMIN)")
    public VenueResponse get(@PathVariable Long venueId) {
        return venueService.get(venueId);
    }
}
