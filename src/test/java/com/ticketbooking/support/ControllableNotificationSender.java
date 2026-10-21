package com.ticketbooking.support;

import com.ticketbooking.notification.NotificationSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for the email channel: records sends and can simulate transient or permanent failures. */
public class ControllableNotificationSender implements NotificationSender {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private volatile String alwaysFailFor;

    @Override
    public void sendEmail(String to, String subject, String body) {
        if (to.equals(alwaysFailFor)) {
            throw new IllegalStateException("simulated permanent email outage for " + to);
        }
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new IllegalStateException("simulated transient email failure");
        }
        sent.add(to + "|" + subject);
    }

    public void failNextSends(int times) {
        failuresRemaining.set(times);
    }

    public void alwaysFailFor(String email) {
        this.alwaysFailFor = email;
    }

    public List<String> sent() {
        return sent;
    }

    public void reset() {
        sent.clear();
        failuresRemaining.set(0);
        alwaysFailFor = null;
    }
}
