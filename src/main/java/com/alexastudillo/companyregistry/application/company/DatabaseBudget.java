package com.alexastudillo.companyregistry.application.company;

import java.time.Duration;

/** Accumulates local database time; external enrichment has its own deadline. */
final class DatabaseBudget {
    private long remaining;
    private long start;
    DatabaseBudget(Duration limit) {remaining=limit.toNanos();}
    void start() {start=System.nanoTime();}
    void pause() {remaining-=System.nanoTime()-start;start=0;}
    Duration remaining() {
        long nanos=remaining-(start==0?0:System.nanoTime()-start);
        if(nanos<=0) throw new CompanyFailure(CompanyFailure.Kind.UNAVAILABLE);
        return Duration.ofNanos(nanos);
    }
}
