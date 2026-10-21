package com.ticketbooking.venue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    boolean existsByNameAndCity(String name, String city);

    java.util.Optional<Venue> findByNameAndCity(String name, String city);
}
