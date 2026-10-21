package com.ticketbooking.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Every time-based decision (hold expiry, cancellation cut-off, JWT validity)
 * reads time from this single UTC clock. Tests substitute a controllable clock,
 * which makes expiry scenarios deterministic instead of sleep-based.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
