package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.outbox.*;
import com.alexastudillo.companyregistry.contract.OutboxFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxTelemetryTest {
    @Test void unavailableDataIsNotZeroAndIdentitiesNeverBecomeMetricLabels() {
        var metrics = new SimpleMeterRegistry();
        try {
            var telemetry = new OutboxTelemetry(metrics);
            assertTrue(Double.isNaN(metrics.get("company.outbox.pending").gauge().value()));
            telemetry.backlog(new DeliveryStore.Backlog(4, 100));
            assertEquals(4, metrics.get("company.outbox.pending").gauge().value());
            assertEquals(100, metrics.get("company.outbox.oldest.age.seconds").gauge().value());
            telemetry.unavailable();
            assertEquals(0, metrics.get("company.outbox.backlog.available").gauge().value());
            assertTrue(Double.isNaN(metrics.get("company.outbox.pending").gauge().value()));
            var e = OutboxFixtures.example("company.created.v1");
            var c = new DeliveryStore.Claim(e, new DeliveryStore.Reservation(e.eventId(), e.tenantId(), 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(30), 1));
            telemetry.observe(c, DeliveryObserver.Signal.CONFIRMED, null);
            telemetry.observe(c, DeliveryObserver.Signal.TRANSITION_UNCERTAIN, null);
            assertNull(metrics.find("company.outbox.delivery").tag("outcome", "published").counter());
            assertEquals(1, metrics.get("company.outbox.delivery").tag("outcome", "confirmed").counter().count());
            assertNull(org.jboss.logging.MDC.get("correlationId"));
            for (var meter : metrics.getMeters()) for (var tag : meter.getId().getTags())
                assertTrue(java.util.Set.of("event_type", "outcome", "code").contains(tag.getKey()));
        } finally { metrics.close(); }
    }
}
