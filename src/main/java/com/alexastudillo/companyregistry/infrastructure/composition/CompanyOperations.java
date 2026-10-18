package com.alexastudillo.companyregistry.infrastructure.composition;

import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

@ApplicationScoped
public class CompanyOperations {
    @Produces public TransitionCompany.Storage lifecycleStorage(AtomicOperation atomic, CompanyRepository companies,
            SiteRepository sites, LifecycleEvents events) {
        return new TransitionCompany.Storage(atomic, companies, sites, events);
    }
    @Produces public TransitionCompany.Idempotency lifecycleIdempotency(CompletionRepository completions,
            LifecycleFingerprints fingerprints, LifecycleResults results) {
        return new TransitionCompany.Idempotency(completions, fingerprints, results);
    }
    @Produces public TransitionCompany lifecycle(TransitionCompany.Storage storage, TransitionCompany.Idempotency idempotency,
            com.alexastudillo.companyregistry.application.reference.PartyReferences parties, Identifiers ids, Clock clock,
            @org.eclipse.microprofile.config.inject.ConfigProperty(name="company-registry.lifecycle.wait-budget",defaultValue="10s") java.time.Duration budget) {
        return new TransitionCompany(storage, idempotency, parties, ids, clock, budget);
    }
    @Produces public ReadCompanies reads(AtomicOperation atomic,CompanyRepository companies,PartyReads parties,CompanyCursors cursors) {return new ReadCompanies(atomic,companies,parties,cursors);}
    @Produces public RegisterCompany.Storage storage(AtomicOperation atomic, CompanyRepository companies, CreationEvents events) {
        return new RegisterCompany.Storage(atomic, companies, events);
    }
    @Produces public RegisterCompany.Idempotency idempotency(CompletionRepository completions, Fingerprints fingerprints, AcceptedResults results) {
        return new RegisterCompany.Idempotency(completions, fingerprints, results);
    }
    @Produces public RegisterCompany registration(RegisterCompany.Storage storage, RegisterCompany.Idempotency idempotency,
            PartyReads parties, Identifiers ids, Clock clock,
            @org.eclipse.microprofile.config.inject.ConfigProperty(name="company-registry.registration.wait-budget",defaultValue="10s") java.time.Duration waitBudget) {
        return new RegisterCompany(storage, idempotency, parties, ids, clock, waitBudget);
    }
    @Produces public UpdateCompany.Storage storage(AtomicOperation atomic, CompanyRepository companies, UpdateEvents events) {
        return new UpdateCompany.Storage(atomic, companies, events);
    }
    @Produces public UpdateCompany.Idempotency idempotency(CompletionRepository completions, UpdateFingerprints fingerprints, UpdateResults results) {
        return new UpdateCompany.Idempotency(completions, fingerprints, results);
    }
    @Produces public UpdateCompany updates(UpdateCompany.Storage storage, UpdateCompany.Idempotency idempotency,
            Identifiers ids, Clock clock,
            @org.eclipse.microprofile.config.inject.ConfigProperty(name="company-registry.update.wait-budget",defaultValue="10s") java.time.Duration budget) {
        return new UpdateCompany(storage, idempotency, ids, clock, budget);
    }

}
