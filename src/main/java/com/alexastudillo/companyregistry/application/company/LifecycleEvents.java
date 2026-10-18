package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import io.smallrye.mutiny.Uni;

public interface LifecycleEvents {
    Uni<OutboxRecord> store(CompanyLifecycleEvent candidate);
    Uni<OutboxRecord> store(CompanyUpdated archivedCandidate);
}
