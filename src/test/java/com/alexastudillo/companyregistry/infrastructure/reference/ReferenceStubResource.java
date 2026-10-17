package com.alexastudillo.companyregistry.infrastructure.reference;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** Configures real HTTP stub URLs; it does not replace CDI clients or native process beans. */
public class ReferenceStubResource implements QuarkusTestResourceLifecycleManager {
    static ReferenceStubServer stub;
    @Override public Map<String, String> start() {
        try { stub = new ReferenceStubServer(); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        return Map.of("quarkus.rest-client.party-registry.url", stub.url(),
                "quarkus.rest-client.geographic-reference.url", stub.url(),
                "quarkus.rest-client.party-registry.read-timeout", "200",
                "quarkus.rest-client.geographic-reference.read-timeout", "200");
    }
    @Override public void stop() { if (stub != null) stub.close(); }
}
