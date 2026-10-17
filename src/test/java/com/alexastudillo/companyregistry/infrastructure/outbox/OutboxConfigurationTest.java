package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks the real configuration mapping, defaults and invalid publisher preparation without a sender. */
class OutboxConfigurationTest {
    private static OutboxConfiguration config(Map<String, String> overrides) {
        return new SmallRyeConfigBuilder().withConverter(Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                .withMapping(OutboxConfiguration.class).withDefaultValues(overrides)
                .build().getConfigMapping(OutboxConfiguration.class);
    }
    @Test void matchesDesignDefaultsAndOverrides() {
        var defaults = config(Map.of()); OutboxConfiguration.validate(defaults); var p = defaults.publisher();
        assertEquals("disabled", defaults.mode()); assertEquals(Duration.ofSeconds(5), p.pollInterval());
        assertEquals(25, p.batchSize()); assertEquals(Duration.ofSeconds(5), p.publishTimeout());
        assertEquals(Duration.ofSeconds(30), p.claimLease()); assertEquals(Duration.ofSeconds(1), p.initialBackoff());
        assertEquals(Duration.ofMinutes(5), p.maximumBackoff()); assertEquals(10, p.maximumAttempts());
        assertEquals("company.events", p.exchange()); assertEquals("company.events", p.routingKey());
        var overridden = config(Map.of("company-registry.outbox.mode", "published", "company-registry.outbox.publisher.batch-size", "31",
                "company-registry.outbox.publisher.poll-interval", "2s", "company-registry.outbox.publisher.exchange", "override.events"));
        OutboxConfiguration.validate(overridden); assertEquals(31, overridden.publisher().batchSize());
        assertEquals(Duration.ofSeconds(2), overridden.publisher().pollInterval()); assertEquals("override.events", overridden.publisher().exchange());
    }
    @Test void rejectsInvalidCountsDurationsBackoffAndDestinations() {
        for (var field : new String[]{"poll-interval", "publish-timeout", "claim-lease", "initial-backoff", "maximum-backoff"}) {
            for (var value : new String[]{"0s", "-1s"}) {
                var configuration = config(Map.of("company-registry.outbox.publisher." + field, value));
                assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(configuration));
            }
            var invalidDuration = Map.of("company-registry.outbox.publisher." + field, "invalid");
            assertThrows(RuntimeException.class, () -> config(invalidDuration));
        }
        for (var field : new String[]{"batch-size", "maximum-attempts"}) {
            for (var value : new String[]{"0", "-1"}) {
                var configuration = config(Map.of("company-registry.outbox.publisher." + field, value));
                assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(configuration));
            }
        }
        for (var field : new String[]{"exchange", "routing-key"}) {
            var configuration = config(Map.of("company-registry.outbox.publisher." + field, "  "));
            assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(configuration));
        }
        var invertedBackoff = config(Map.of(
                "company-registry.outbox.publisher.initial-backoff", "6m", "company-registry.outbox.publisher.maximum-backoff", "5m"));
        assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(invertedBackoff));
    }
}

