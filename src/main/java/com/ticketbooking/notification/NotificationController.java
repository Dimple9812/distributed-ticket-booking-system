package com.ticketbooking.notification;

import com.ticketbooking.common.PageResponse;
import com.ticketbooking.notification.NotificationService.NotificationResponse;
import com.ticketbooking.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/v1/notifications")
    @Operation(summary = "Your in-app notifications, produced asynchronously from Kafka booking events")
    public PageResponse<NotificationResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return notificationService.list(user.id(), PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
