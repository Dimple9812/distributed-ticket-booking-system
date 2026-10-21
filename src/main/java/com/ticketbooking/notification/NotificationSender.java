package com.ticketbooking.notification;

/** Outbound notification channel (email/SMS/push). Only a logging mock exists in this project. */
public interface NotificationSender {

    void sendEmail(String to, String subject, String body);
}
