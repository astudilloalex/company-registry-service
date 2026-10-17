package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import io.smallrye.mutiny.Uni;

public interface UpdateEvents {
    /** Called in the outer aggregate/completion transaction. Disabled mode omits storage. */
    Uni<OutboxRecord> store(CompanyUpdated candidate);
}
