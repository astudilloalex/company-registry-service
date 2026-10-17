package com.alexastudillo.companyregistry.infrastructure.composition;

import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.persistence.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

@ApplicationScoped
public class CompanyOperations {
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
}
