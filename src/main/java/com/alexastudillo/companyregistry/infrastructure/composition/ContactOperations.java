package com.alexastudillo.companyregistry.infrastructure.composition;

import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.application.persistence.*;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ContactOperations {
    @Produces public ReadContacts reads(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites, ContactCursors cursors) {
        return new ReadContacts(atomic, companies, sites, cursors);
    }

    @Produces public MutateContact.Storage storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteMutationEvents events) {
        return new MutateContact.Storage(atomic, companies, sites, events);
    }

    @Produces public MutateContact.Idempotency idempotency(CompletionRepository completions, ContactFingerprints fingerprints, ContactResults results) {
        return new MutateContact.Idempotency(completions, fingerprints, results);
    }

    // Validate the budget before startup completes, even when no contact request arrives.
    @Produces @Startup public MutateContact mutation(MutateContact.Storage storage, MutateContact.Idempotency idempotency,
            Identifiers ids, Clock clock,
            @ConfigProperty(name = "company-registry.site-contact.wait-budget", defaultValue = "10s") Duration budget) {
        return new MutateContact(storage, idempotency, ids, clock, budget);
    }
}
