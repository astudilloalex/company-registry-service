package com.alexastudillo.companyregistry.infrastructure.outbox;

import com.alexastudillo.companyregistry.application.outbox.DeliveryFailure;
import com.alexastudillo.companyregistry.application.outbox.DeliveryObserver;
import com.alexastudillo.companyregistry.application.outbox.DeliveryStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@Startup
@ApplicationScoped
public class OutboxTelemetry implements DeliveryObserver {
    private static final Logger LOG = Logger.getLogger(OutboxTelemetry.class);
    private static final String CORRELATION_ID = "correlationId";
    private final MeterRegistry metrics;
    private volatile double pending = Double.NaN;
    private volatile double oldest = Double.NaN;
    private volatile double available;

    @Inject public OutboxTelemetry(MeterRegistry metrics) {
        this.metrics = metrics;
        metrics.gauge("company.outbox.pending", this, t -> t.pending);
        metrics.gauge("company.outbox.oldest.age.seconds", this, t -> t.oldest);
        metrics.gauge("company.outbox.backlog.available", this, t -> t.available);
    }

    @Override public void observe(DeliveryStore.Claim claim, Signal signal, DeliveryFailure.Code error) {
        String type = OutboxDestination.EVENT_TYPES.contains(claim.event().kind().type()) ? claim.event().kind().type() : "unsupported";
        String code = error == null ? "none" : error.diagnostic();
        metrics.counter("company.outbox.delivery", "event_type", type, "outcome", signal.name().toLowerCase(java.util.Locale.ROOT), "code", code).increment();
        Object previous = MDC.get(CORRELATION_ID);
        try {
            String correlation = safeCorrelation(claim.event().context().correlationId());
            MDC.put(CORRELATION_ID, correlation);
            LOG.infof("Outbox eventId=%s tenantId=%s correlationId=%s attempt=%d outcome=%s code=%s",
                    claim.reservation().eventId(), claim.reservation().tenantId(), correlation, claim.reservation().attempt(), signal, code);
        } finally {
            if (previous == null) MDC.remove(CORRELATION_ID); else MDC.put(CORRELATION_ID, previous);
        }
    }

    private static String safeCorrelation(String value) {
        try { return java.util.UUID.fromString(value).toString(); }
        catch (RuntimeException _) { return "invalid"; }
    }

    public void exhausted(int count) { if (count > 0) metrics.counter("company.outbox.exhausted").increment(count); }
    public void backlog(DeliveryStore.Backlog value) {
        pending = value.pending(); oldest = value.oldestAgeSeconds(); available = 1;
    }
    public void unavailable() {
        available = 0; pending = Double.NaN; oldest = Double.NaN;
        metrics.counter("company.outbox.poll.failures").increment();
        LOG.warn("Outbox delivery unavailable code=persistence-unavailable");
    }
}
