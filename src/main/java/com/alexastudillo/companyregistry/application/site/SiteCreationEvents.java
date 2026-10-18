package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.OutboxRecord;
import io.smallrye.mutiny.Uni;

public interface SiteCreationEvents {
    /** Called in the outer aggregate/completion transaction. Disabled mode omits storage. */
    Uni<OutboxRecord> store(SiteCreated candidate);
}
