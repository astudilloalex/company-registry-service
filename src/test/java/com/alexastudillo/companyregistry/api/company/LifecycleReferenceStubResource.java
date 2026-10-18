package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

public class LifecycleReferenceStubResource implements QuarkusTestResourceLifecycleManager {
    static ReferenceStubServer stub;
    @Override public Map<String, String> start() {
        try { stub = new ReferenceStubServer(); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        return Map.of("quarkus.rest-client.party-registry.url", stub.url(),
                "quarkus.rest-client.geographic-reference.url", stub.url(),
                "quarkus.rest-client.party-registry.read-timeout", "1500",
                "company-registry.outbox.mode", "stored-only");
    }
    @Override public void stop() { if (stub != null) stub.close(); }
}
