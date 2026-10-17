package com.alexastudillo.companyregistry.infrastructure.reference;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Uses deliberately unavailable external URLs to prove startup has no provider availability dependency. */
public class OfflineStartupProfile implements QuarkusTestProfile {
    @Override public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.rest-client.party-registry.url", "http://127.0.0.1:9",
                "quarkus.rest-client.geographic-reference.url", "http://127.0.0.1:9");
    }
}
