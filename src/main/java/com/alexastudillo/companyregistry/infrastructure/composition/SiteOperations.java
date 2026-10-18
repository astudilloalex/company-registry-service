package com.alexastudillo.companyregistry.infrastructure.composition;

import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.application.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SiteOperations {
    @Produces public ReadSites reads(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites, SiteCursors cursors) {
        return new ReadSites(atomic, companies, sites, cursors);
    }
    @Produces public RegisterSite.Storage storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteCreationEvents events) {
        return new RegisterSite.Storage(atomic, companies, sites, events);
    }
    @Produces public RegisterSite.Idempotency idempotency(CompletionRepository completions, SiteFingerprints fingerprints,
            SiteResults results) {
        return new RegisterSite.Idempotency(completions, fingerprints, results);
    }
    @Produces public RegisterSite registration(RegisterSite.Storage storage, RegisterSite.Idempotency idempotency,
            Identifiers ids, Clock clock,
            @ConfigProperty(name="company-registry.site-registration.wait-budget", defaultValue="10s") Duration budget) {
        return new RegisterSite(storage, idempotency, ids, clock, budget);
    }
    @Produces public MutateSite.Storage mutationStorage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteMutationEvents events) {
        return new MutateSite.Storage(atomic, companies, sites, events);
    }
    @Produces public MutateSite.Idempotency mutationIdempotency(CompletionRepository completions, SiteMutationFingerprints fingerprints,
            SiteMutationResults results) {
        return new MutateSite.Idempotency(completions, fingerprints, results);
    }
    @Produces public MutateSite mutation(MutateSite.Storage storage, MutateSite.Idempotency idempotency,
            Identifiers ids, Clock clock,
            @ConfigProperty(name="company-registry.site-mutation.wait-budget", defaultValue="10s") Duration budget) {
        return new MutateSite(storage, idempotency, ids, clock, budget);
    }
}
