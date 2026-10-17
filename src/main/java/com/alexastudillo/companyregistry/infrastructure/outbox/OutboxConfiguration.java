package com.alexastudillo.companyregistry.infrastructure.outbox;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Externally configurable foundation settings; no broker connection is derived from them in T00. */
@ConfigMapping(prefix = "company-registry.outbox")
public interface OutboxConfiguration {
    @WithDefault("disabled") String mode();
    Publisher publisher();

    interface Publisher {
        @WithDefault("5s") java.time.Duration pollInterval();
        @WithDefault("25") int batchSize();
        @WithDefault("5s") java.time.Duration publishTimeout();
        @WithDefault("30s") java.time.Duration claimLease();
        @WithDefault("1s") java.time.Duration initialBackoff();
        @WithDefault("5m") java.time.Duration maximumBackoff();
        @WithDefault("10") int maximumAttempts();
        @WithDefault("company.events") String exchange();
        @WithDefault("company.events") String routingKey();
    }
    static void validate(OutboxConfiguration config) {
        OutboxMode.parse(config.mode());
        var p = config.publisher();
        for (var duration : new java.time.Duration[]{p.pollInterval(), p.publishTimeout(), p.claimLease(), p.initialBackoff(), p.maximumBackoff()}) {
            if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("outbox-duration-must-be-positive");
        }
        if (p.batchSize() < 1 || p.maximumAttempts() < 1) throw new IllegalArgumentException("outbox-count-must-be-positive");
        if (p.maximumBackoff().compareTo(p.initialBackoff()) < 0) throw new IllegalArgumentException("outbox-backoff-inverted");
        if (p.exchange().isBlank() || p.routingKey().isBlank()) throw new IllegalArgumentException("outbox-destination-required");
    }
}
