package com.alexastudillo.companyregistry.infrastructure.verification;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.verification.PersistenceVerification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** Verification-only outer wiring, absent from the production compilation. */
@ApplicationScoped
public class VerificationComposition {
    @Produces @ApplicationScoped
    public PersistenceVerification persistence(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
                                               CompletionRepository completions, OutboxRepository events, Identifiers ids) {
        return new PersistenceVerification(atomic, companies, sites, completions, events, ids);
    }
}
