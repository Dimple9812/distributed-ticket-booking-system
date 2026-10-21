package com.ticketbooking.payment;

import java.math.BigDecimal;

/**
 * Port to a payment provider. Only a simulated implementation exists in this project;
 * the booking flow never accepts a payment status from the client, it asks the gateway.
 */
public interface PaymentGateway {

    GatewayResult charge(String paymentMethodToken, BigDecimal amount, String currency, String idempotencyReference);

    GatewayResult refund(String providerReference, BigDecimal amount, String currency);

    record GatewayResult(boolean success, String providerReference, String failureReason) {
    }
}
