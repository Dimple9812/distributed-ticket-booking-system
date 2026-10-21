package com.ticketbooking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    public ControllableNotificationSender controllableNotificationSender() {
        return new ControllableNotificationSender();
    }
}
