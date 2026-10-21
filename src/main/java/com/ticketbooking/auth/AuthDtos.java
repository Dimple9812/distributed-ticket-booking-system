package com.ticketbooking.auth;

import com.ticketbooking.user.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) @Schema(example = "asha@example.com") String email,
            // BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated.
            @NotBlank @Size(min = 8, max = 72) @Schema(example = "S3cure-pass!") String password,
            @NotBlank @Size(max = 120) @Schema(example = "Asha Verma") String fullName) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {
    }
}
