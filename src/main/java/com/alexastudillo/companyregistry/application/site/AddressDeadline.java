package com.alexastudillo.companyregistry.application.site;

import java.time.Duration;

/** One monotonic HU06 budget including sessions, locks, HTTP, commit, close and recovery. */
public final class AddressDeadline {
    private static final String INVALID = "site-address-budget-invalid";
    private final long limit;
    private final long started = System.nanoTime();

    public AddressDeadline(Duration budget) { limit = validate(budget); }

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
        if (nanos <= 0) throw new AddressFailure(AddressFailure.Kind.UNAVAILABLE);
        return Duration.ofNanos(nanos);
    }

    public void check() {
        if (System.nanoTime() - started >= limit) throw new AddressFailure(AddressFailure.Kind.UNAVAILABLE);
    }
}
