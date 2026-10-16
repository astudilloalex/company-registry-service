package com.alexastudillo.companyregistry.infrastructure.composition;

import java.time.Clock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class TimeConfiguration {
    @Produces
    public Clock clock() {
        return Clock.systemUTC();
    }
}
