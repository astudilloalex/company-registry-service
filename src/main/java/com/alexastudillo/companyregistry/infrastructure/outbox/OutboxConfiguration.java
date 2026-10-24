package com.alexastudillo.companyregistry.infrastructure.outbox;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/** Runtime settings shared by all three modes of the same artifact. */
@ConfigMapping(prefix = "company-registry.outbox")
public interface OutboxConfiguration {
    @WithDefault("disabled") String mode();
    Publisher publisher();
    Broker broker();

    interface Broker {
        Optional<String> host();
        @WithDefault("5672") int port();
        Optional<String> username();
        Optional<String> password();
        @WithDefault("platform") String virtualHost();
    }

    interface Publisher {
        @WithDefault("5s") java.time.Duration pollInterval();
        @WithDefault("25") int batchSize();
        @WithDefault("5s") java.time.Duration publishTimeout();
        @WithDefault("30s") java.time.Duration claimLease();
        @WithDefault("1s") java.time.Duration initialBackoff();
        @WithDefault("5m") java.time.Duration maximumBackoff();
        @WithDefault("10") int maximumAttempts();
        @WithDefault("company.events") String exchange();
        @WithDefault("%s") String routingKey();
    }
    static void validate(OutboxConfiguration config) {
        OutboxMode.parse(config.mode());
        var p = config.publisher();
        duration(p.pollInterval(), Duration.ofHours(1));
        duration(p.publishTimeout(), Duration.ofMinutes(5));
        duration(p.claimLease(), Duration.ofHours(1));
        duration(p.initialBackoff(), Duration.ofHours(24));
        duration(p.maximumBackoff(), Duration.ofHours(24));
        if (p.batchSize() < 1 || p.batchSize() > 500 || p.maximumAttempts() < 1 || p.maximumAttempts() > 100)
            throw new IllegalArgumentException("outbox-count-out-of-range");
        if (p.claimLease().compareTo(p.publishTimeout()) <= 0) throw new IllegalArgumentException("outbox-lease-must-exceed-timeout");
        if (p.maximumBackoff().compareTo(p.initialBackoff()) < 0) throw new IllegalArgumentException("outbox-backoff-inverted");
        OutboxDestination.validate(p.exchange(), p.routingKey());
    }

    private static void duration(Duration value, Duration maximum) {
        if (value.isNegative() || value.isZero() || value.compareTo(maximum) > 0 || value.toNanos() < 1)
            throw new IllegalArgumentException("outbox-duration-out-of-range");
    }

    static void validateBroker(OutboxConfiguration config) {
        if (OutboxMode.parse(config.mode()) != OutboxMode.PUBLISHED) return;
        var b = config.broker();
        if (b.host().filter(s -> !s.isBlank()).isEmpty() || b.username().filter(s -> !s.isBlank()).isEmpty()
                || b.password().filter(s -> !s.isBlank()).isEmpty() || b.port() < 1 || b.port() > 65535
                || b.virtualHost().isBlank()) throw new IllegalArgumentException("outbox-broker-configuration-invalid");
    }
}
