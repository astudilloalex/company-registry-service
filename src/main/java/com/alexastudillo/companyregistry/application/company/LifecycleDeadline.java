package com.alexastudillo.companyregistry.application.company;

import java.time.Duration;

/** One monotonic request budget, including the time spent outside the database. */
public final class LifecycleDeadline {
    private static final String INVALID_BUDGET = "company-lifecycle-budget-invalid";
    private final long limit;
    private final long started;

    public LifecycleDeadline(Duration budget) {
        limit = validate(budget);
        started = System.nanoTime();
    }

    public static long validate(Duration budget) {
        if (budget == null) throw new IllegalArgumentException(INVALID_BUDGET);
        try {
            long nanos = budget.toNanos();
            if (nanos <= 0) throw new IllegalArgumentException(INVALID_BUDGET);
            return nanos;
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException(INVALID_BUDGET);
        }
    }

    public Duration remaining() {
        long remaining = limit - (System.nanoTime() - started);
        if (remaining <= 0) throw new CompanyLifecycleFailure(CompanyLifecycleFailure.Kind.UNAVAILABLE);
        return Duration.ofNanos(remaining);
    }

    public void check() {
        if (System.nanoTime() - started >= limit) {
            throw new CompanyLifecycleFailure(CompanyLifecycleFailure.Kind.UNAVAILABLE);
        }
    }
}
