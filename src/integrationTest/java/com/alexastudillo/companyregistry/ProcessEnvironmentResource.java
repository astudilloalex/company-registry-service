package com.alexastudillo.companyregistry;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import java.util.HashMap;
import java.util.Map;

/** Supplies external PostgreSQL and URL-based stubs before the packaged/native process starts. */
public class ProcessEnvironmentResource extends PostgresResource {
    static ReferenceStubServer stub;
    static Map<String, String> settings;
    @Override public Map<String, String> start() {
        var values = new HashMap<>(super.start());
        try { stub = new ReferenceStubServer(); }
        catch (java.io.IOException failure) { super.stop(); throw new IllegalStateException(failure); }
        values.put("quarkus.rest-client.party-registry.url", stub.url());
        values.put("quarkus.rest-client.geographic-reference.url", stub.url());
        values.put("quarkus.rest-client.party-registry.read-timeout", "300");
        values.put("quarkus.rest-client.geographic-reference.read-timeout", "300");
        values.put("company-registry.outbox.mode", "stored-only");
        // HU01–HU07 verify capture/history in every policy. HU08 has an independent
        // delivery fixture; never let a regression publisher drain these accepted facts.
        values.put("quarkus.scheduler.enabled", "false");
        values.put("company-registry.outbox.broker.host", "127.0.0.1");
        values.put("company-registry.outbox.broker.port", "9");
        values.put("company-registry.outbox.broker.username", "capture-regression");
        values.put("company-registry.outbox.broker.password", "test-only-unused");
        values.put("company-registry.registration.wait-budget", "5s");
        values.put("company-registry.idempotency.active-key-id", "process-k1");
        values.put("company-registry.idempotency.keys.process-k1", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        values.put("company-registry.idempotency.keys.process-k2", java.util.Base64.getEncoder().encodeToString("independent-process-key-material2".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        settings = Map.copyOf(values);
        return values;
    }
    @Override public void stop() { try { if (stub != null) stub.close(); } finally { super.stop(); } }
}
