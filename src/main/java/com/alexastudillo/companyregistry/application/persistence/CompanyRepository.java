package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import java.util.UUID;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;

public interface CompanyRepository {
    Uni<CompanyRecord> find(UUID tenantId, UUID companyId);
    Uni<CompanyRecord> insert(CompanyRecord company);
}
