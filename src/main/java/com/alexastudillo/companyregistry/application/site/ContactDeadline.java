package com.alexastudillo.companyregistry.application.site;

import java.time.Duration;

/** One monotonic HU07 budget including sessions, locks, HTTP, commit, close and recovery. */
public final class ContactDeadline {
    private static final String INVALID = "site-contact-budget-invalid";
    private final long limit;
    private final long started = System.nanoTime();

    public ContactDeadline(Duration budget) { limit = validate(budget); }

    public static long validate(Duration budget) {
        if (budget == null) throw new IllegalArgumentException(INVALID);
        try {
            long nanos = budget.toNanos();
            if (nanos <= 0) throw new IllegalArgumentException(INVALID);
            return nanos;
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public Duration remaining() {
        long nanos = limit - (System.nanoTime() - started);
        if (nanos <= 0) throw new ContactFailure(ContactFailure.Kind.UNAVAILABLE);
        return Duration.ofNanos(nanos);
    }

    public void check() {
        if (System.nanoTime() - started >= limit) throw new ContactFailure(ContactFailure.Kind.UNAVAILABLE);
    }
}
