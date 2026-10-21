package com.ticketbooking.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mock email adapter: logs the message instead of contacting an SMTP/email provider. */
@Component
public class LoggingEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendEmail(String to, String subject, String body) {
        log.info("[mock-email] to={} subject=\"{}\"", to, subject);
    }
}
