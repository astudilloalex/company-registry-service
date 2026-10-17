package com.alexastudillo.companyregistry.infrastructure.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.UUID;

@ApplicationScoped
public class UuidV7Generator implements com.alexastudillo.companyregistry.application.persistence.Identifiers {
    private static final SecureRandom RANDOM = new SecureRandom();

    public UUID next() {
        long timestamp = System.currentTimeMillis() & 0xffffffffffffL;
        long high = (timestamp << 16) | 0x7000L | (RANDOM.nextLong() & 0xfffL);
        long low = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(high, low);
    }
}
