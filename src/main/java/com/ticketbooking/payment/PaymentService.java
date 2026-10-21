package com.ticketbooking.payment;

import com.ticketbooking.exception.ApiException;
import com.ticketbooking.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentGateway gateway;
    private final PaymentAttemptRepository repository;
    private final TransactionTemplate independentTx;
    private final Clock clock;

    public PaymentService(PaymentGateway gateway, PaymentAttemptRepository repository,
                          PlatformTransactionManager txManager, Clock clock) {
        this.gateway = gateway;
        this.repository = repository;
        this.clock = clock;
        this.independentTx = new TransactionTemplate(txManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Asks the (simulated) provider to charge the customer and returns the provider reference.
     * A decline is recorded in an independent transaction - so the audit row survives the
     * booking rollback - and surfaces as 402 PAYMENT_DECLINED.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String charge(Long userId, UUID holdId, BigDecimal amount, String currency, String paymentMethodToken) {
        PaymentGateway.GatewayResult result = gateway.charge(paymentMethodToken, amount, currency, holdId.toString());
        if (!result.success()) {
            independentTx.executeWithoutResult(status -> repository.save(new PaymentAttempt(userId, holdId, null, amount,
                    currency, PaymentStatus.DECLINED, null, result.failureReason(), clock.instant())));
            throw new ApiException(ErrorCode.PAYMENT_DECLINED, "Payment was declined: " + result.failureReason(),
                    Map.of("reason", result.failureReason()));
        }
        return result.providerReference();
    }

    /** Records a successful charge inside the booking transaction (commits or rolls back with the booking). */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentAttempt recordCharge(Long userId, UUID holdId, UUID bookingId, BigDecimal amount, String currency,
                                       String providerReference) {
        return repository.save(new PaymentAttempt(userId, holdId, bookingId, amount, currency, PaymentStatus.SUCCEEDED,
                providerReference, null, clock.instant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentAttempt refund(Long userId, UUID holdId, UUID bookingId, String originalReference, BigDecimal amount,
                                 String currency) {
        PaymentGateway.GatewayResult result = gateway.refund(originalReference, amount, currency);
        if (!result.success()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Refund failed: " + result.failureReason());
        }
        return repository.save(new PaymentAttempt(userId, holdId, bookingId, amount, currency, PaymentStatus.REFUNDED,
                result.providerReference(), null, clock.instant()));
    }
}
