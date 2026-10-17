package com.alexastudillo.companyregistry.infrastructure.reference;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Rejects unbounded/nonpositive transport timeout configuration without contacting providers at boot. */
@Startup @ApplicationScoped
public class ReferenceClientConfiguration {
    @ConfigProperty(name = "quarkus.rest-client.party-registry.connect-timeout") long partyConnect;
    @ConfigProperty(name = "quarkus.rest-client.party-registry.read-timeout") long partyRead;
    @ConfigProperty(name = "quarkus.rest-client.geographic-reference.connect-timeout") long geographicConnect;
    @ConfigProperty(name = "quarkus.rest-client.geographic-reference.read-timeout") long geographicRead;
    @PostConstruct void validate() {
        for (long timeout : new long[]{partyConnect, partyRead, geographicConnect, geographicRead}) {
            if (timeout < 1 || timeout > Integer.MAX_VALUE) throw new IllegalArgumentException("reference-timeout-must-be-positive-finite-milliseconds");
        }
    }
}
