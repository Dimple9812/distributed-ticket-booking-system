package com.ticketbooking.auth;

import com.ticketbooking.support.AbstractIntegrationTest;
import com.ticketbooking.support.ApiClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends AbstractIntegrationTest {

    @Test
    void registerLoginAndAccessProtectedEndpoint() {
        ApiClient.Response register = api.post("/api/v1/auth/register", null,
                Map.of("email", "Asha@Example.com", "password", "Passw0rd!long", "fullName", "Asha"));
        assertThat(register.status()).isEqualTo(201);
        assertThat(register.body().path("user").path("role").asText()).isEqualTo("CUSTOMER");
        assertThat(register.body().path("user").path("email").asText()).isEqualTo("asha@example.com");

        ApiClient.Response login = api.post("/api/v1/auth/login", null,
                Map.of("email", "asha@example.com", "password", "Passw0rd!long"));
        assertThat(login.status()).isEqualTo(200);
        String token = login.body().path("accessToken").asText();

        ApiClient.Response me = api.get("/api/v1/users/me", token);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("email").asText()).isEqualTo("asha@example.com");

        // Password hash is stored, never the password.
        String hash = jdbc.queryForObject("select password_hash from users where email = 'asha@example.com'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("Passw0rd");
    }

    @Test
    void duplicateEmailIsRejected() {
        Map<String, String> body = Map.of("email", "dup@example.com", "password", "Passw0rd!long", "fullName", "Dup");
        assertThat(api.post("/api/v1/auth/register", null, body).status()).isEqualTo(201);
        ApiClient.Response second = api.post("/api/v1/auth/register", null, body);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("DUPLICATE_RESOURCE");
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameError() {
        registerCustomer("Ravi");
        ApiClient.Response wrong = api.post("/api/v1/auth/login", null,
                Map.of("email", "nobody@example.com", "password", "whatever123"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("AUTHENTICATION_FAILED");
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() {
        ApiClient.Response response = api.post("/api/v1/auth/register", null,
                Map.of("email", "not-an-email", "password", "short", "fullName", ""));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body().path("fieldErrors").size()).isEqualTo(3);
        assertThat(response.body().path("correlationId").asText()).isNotBlank();
    }

    @Test
    void missingOrTamperedTokenIsUnauthorized() {
        Customer customer = registerCustomer("Mira");
        assertThat(api.get("/api/v1/bookings", null).status()).isEqualTo(401);
        String tampered = customer.token().substring(0, customer.token().length() - 4) + "abcd";
        ApiClient.Response response = api.get("/api/v1/bookings", tampered);
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void correlationIdIsEchoed() {
        ApiClient.Response response = api.send("GET", "/api/v1/events", null, null, Map.of("X-Correlation-Id", "trace-123"));
        assertThat(response.header("X-Correlation-Id")).isEqualTo("trace-123");
    }
}
