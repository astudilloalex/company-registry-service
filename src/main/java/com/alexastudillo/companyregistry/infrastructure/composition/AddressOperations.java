package com.alexastudillo.companyregistry.infrastructure.composition;

import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.reference.CountryReferences;
import com.alexastudillo.companyregistry.application.reference.DivisionReferences;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AddressOperations {
    @Produces public ReadAddresses reads(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites, AddressCursors cursors) {
        return new ReadAddresses(atomic, companies, sites, cursors);
    }

    @Produces public MutateAddress.Storage storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteMutationEvents events) {
        return new MutateAddress.Storage(atomic, companies, sites, events);
    }

    @Produces public MutateAddress.Idempotency idempotency(CompletionRepository completions, AddressFingerprints fingerprints, AddressResults results) {
        return new MutateAddress.Idempotency(completions, fingerprints, results);
    }

    @Produces public AddressGeography geography(CountryReferences countries, DivisionReferences divisions) {
        return new AddressGeography(countries, divisions);
    }

    // Validate the budget before startup completes, even when no address request arrives.
    @Produces @Startup public MutateAddress mutation(MutateAddress.Storage storage, MutateAddress.Idempotency idempotency,
            AddressGeography geography, Identifiers ids, Clock clock,
            @ConfigProperty(name = "company-registry.site-address.wait-budget", defaultValue = "10s") Duration budget) {
        return new MutateAddress(storage, idempotency, geography, ids, clock, budget);
    }
}
