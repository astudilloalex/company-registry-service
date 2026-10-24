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
        assertEquals("company.events", p.exchange()); assertEquals("%s", p.routingKey());
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

    @Test void rejectsUpperBoundsLeaseAndUnsafeRoutingInEveryMode() {
        var invalid = Map.of("poll-interval", "3601s", "batch-size", "501", "publish-timeout", "301s",
                "claim-lease", "3601s", "initial-backoff", "86401s", "maximum-backoff", "86401s",
                "maximum-attempts", "101");
        for (var mode : new String[]{"disabled", "stored-only", "published"}) {
            invalid.forEach((key, value) -> {
                var configuration = config(Map.of("company-registry.outbox.mode", mode,
                        "company-registry.outbox.publisher." + key, value));
                assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(configuration));
            });
        }
        var same = config(Map.of("company-registry.outbox.publisher.claim-lease", "5s"));
        assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(same));
        var huge = config(Map.of("company-registry.outbox.publisher.maximum-backoff", "PT9223372036854775807S"));
        assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(huge));
        for (var template : new String[]{"company.events", "%s.%s", "%d", "%1$s", "%s%", "a\n%s", "é".repeat(120) + ".%s"}) {
            var configuration = config(Map.of("company-registry.outbox.publisher.routing-key", template));
            assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validate(configuration));
        }
    }

    @Test void validatesBoundariesDestinationsAndBrokerOnlyWhenPublished() {
        var boundary = config(Map.of("company-registry.outbox.publisher.poll-interval", "1h",
                "company-registry.outbox.publisher.batch-size", "500", "company-registry.outbox.publisher.publish-timeout", "5m",
                "company-registry.outbox.publisher.claim-lease", "1h", "company-registry.outbox.publisher.initial-backoff", "24h",
                "company-registry.outbox.publisher.maximum-backoff", "24h", "company-registry.outbox.publisher.maximum-attempts", "100"));
        assertDoesNotThrow(() -> OutboxConfiguration.validate(boundary));
        for (var type : OutboxDestination.EVENT_TYPES) {
            assertEquals(type, OutboxDestination.route("%s", type));
            assertEquals("integration." + type, OutboxDestination.route("integration.%s", type));
        }
        for (var mode : new String[]{"disabled", "stored-only"}) {
            var configuration = config(Map.of("company-registry.outbox.mode", mode));
            assertDoesNotThrow(() -> OutboxConfiguration.validateBroker(configuration));
        }
        var missing = config(Map.of("company-registry.outbox.mode", "published"));
        var failure = assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validateBroker(missing));
        assertEquals("outbox-broker-configuration-invalid", failure.getMessage());
        var properties = new java.util.HashMap<>(Map.of("company-registry.outbox.mode", "published",
                "company-registry.outbox.broker.host", "localhost", "company-registry.outbox.broker.username", "test",
                "company-registry.outbox.broker.password", "disposable"));
        var valid = config(properties);
        assertEquals("platform", valid.broker().virtualHost());
        assertDoesNotThrow(() -> OutboxConfiguration.validateBroker(valid));
        for (var port : new String[]{"0", "65536"}) {
            properties.put("company-registry.outbox.broker.port", port);
            var configuration = config(properties);
            assertThrows(IllegalArgumentException.class, () -> OutboxConfiguration.validateBroker(configuration));
        }
    }
}

