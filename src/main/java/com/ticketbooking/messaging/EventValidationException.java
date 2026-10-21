package com.ticketbooking.messaging;

/**
 * A message that can never be processed successfully (malformed JSON, missing fields,
 * unknown type or version). Not retried: it goes straight to the dead-letter topic.
 */
public class EventValidationException extends RuntimeException {

    public EventValidationException(String message) {
        super(message);
    }

    public EventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
