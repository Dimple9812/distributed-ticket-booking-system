package com.ticketbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

// JWT authentication replaces Boot's default in-memory user (and its generated password).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class TicketBookingApplication {

    static {
        // Run the JVM in UTC regardless of host settings. Besides consistent timestamps this avoids
        // the PostgreSQL driver sending legacy zone aliases (e.g. "Asia/Calcutta") that the server rejects.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(TicketBookingApplication.class, args);
    }
}
