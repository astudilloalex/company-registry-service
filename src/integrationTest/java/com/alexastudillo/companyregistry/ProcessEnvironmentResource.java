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
        values.put("company-registry.outbox.mode", "disabled");
        settings = Map.copyOf(values);
        return values;
    }
    @Override public void stop() { try { if (stub != null) stub.close(); } finally { super.stop(); } }
}
