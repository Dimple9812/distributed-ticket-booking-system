package com.ticketbooking.security;

import com.ticketbooking.user.Role;

/** Principal extracted from a verified JWT. Controllers receive it via @AuthenticationPrincipal. */
public record AuthenticatedUser(Long id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
