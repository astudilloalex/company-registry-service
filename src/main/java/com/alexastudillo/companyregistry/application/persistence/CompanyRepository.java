package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import java.util.UUID;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;

public interface CompanyRepository {
    Uni<CompanyRecord> find(UUID tenantId, UUID companyId);
    Uni<CompanyRecord> insert(CompanyRecord company);
    Uni<Boolean> occupied(UUID tenantId, String code, UUID partyId);
    /** At most limit+1 rows, ordered by createdAt/id descending. */
    Uni<java.util.List<CompanyRecord>> page(UUID tenantId,com.alexastudillo.companyregistry.application.company.CompanySelection.Filters filters,
            com.alexastudillo.companyregistry.application.company.CompanySelection.Boundary boundary,int limit);
}
