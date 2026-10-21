package com.ticketbooking.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RedisConfig {

    /**
     * Fail fast when Redis is down. Lettuce's default is to queue commands while
     * disconnected, which would make every hold request hang until the command
     * timeout. Rejecting immediately lets the lock service apply its configured
     * failure mode (DB_ONLY or FAIL_CLOSED) within milliseconds.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer failFastRedis() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
                .build());
    }
}
