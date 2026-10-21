package com.ticketbooking.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Helpers for running non-transactional side effects (Redis lock cleanup) only
 * once the database outcome is known. Running them inside the transaction would
 * be wrong: a lock could be released for a change that later rolls back.
 */
public final class AfterTransaction {

    private static final Logger log = LoggerFactory.getLogger(AfterTransaction.class);

    private AfterTransaction() {
    }

    public static void onCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action);
            }
        });
    }

    /** Runs when the transaction rolls back or its outcome is unknown. */
    public static void onRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    runSafely(action);
                }
            }
        });
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            // Post-transaction cleanup must never turn a committed request into a failure.
            log.warn("Post-transaction action failed: {}", ex.getMessage());
        }
    }
}
