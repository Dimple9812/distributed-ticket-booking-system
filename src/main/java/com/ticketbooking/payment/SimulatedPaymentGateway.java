package com.ticketbooking.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deterministic fake provider for demos and tests. Decisions depend only on the
 * payment-method token, mirroring how real providers expose test cards:
 * <ul>
 *   <li>{@code pm_card_declined} - declined</li>
 *   <li>{@code pm_card_insufficient_funds} - declined</li>
 *   <li>any other {@code pm_*} token - approved</li>
 * </ul>
 * No real money moves and no external call is made.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    @Override
    public GatewayResult charge(String paymentMethodToken, BigDecimal amount, String currency, String idempotencyReference) {
        return switch (paymentMethodToken) {
            case "pm_card_declined" -> new GatewayResult(false, null, "card_declined");
            case "pm_card_insufficient_funds" -> new GatewayResult(false, null, "insufficient_funds");
            default -> new GatewayResult(true, "sim_ch_" + UUID.randomUUID().toString().replace("-", ""), null);
        };
    }

    @Override
    public GatewayResult refund(String providerReference, BigDecimal amount, String currency) {
        return new GatewayResult(true, "sim_re_" + UUID.randomUUID().toString().replace("-", ""), null);
    }
}
