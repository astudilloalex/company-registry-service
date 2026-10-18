package com.alexastudillo.companyregistry.application.company;

import java.time.Duration;

/** Accumulates local database time; external enrichment has its own deadline. */
public final class DatabaseBudget {
    private long remaining;
    private long start;
    public DatabaseBudget(Duration limit) {remaining=limit.toNanos();}
    public void start() {start=System.nanoTime();}
    public void pause() {remaining-=System.nanoTime()-start;start=0;}
    public Duration remaining() {
        long nanos=remaining-(start==0?0:System.nanoTime()-start);
        if(nanos<=0) throw new CompanyFailure(CompanyFailure.Kind.UNAVAILABLE);
        return Duration.ofNanos(nanos);
    }
}
