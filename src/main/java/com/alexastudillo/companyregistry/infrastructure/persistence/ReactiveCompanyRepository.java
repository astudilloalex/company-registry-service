package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class ReactiveCompanyRepository implements CompanyRepository, PanacheRepositoryBase<CompanyEntity, UUID> {
    @Override public Uni<CompanyRecord> find(UUID tenantId, UUID companyId) {
        return find("tenantId = ?1 and id = ?2", tenantId, companyId).firstResult()
                .map(row -> row == null ? null : row.value());
    }
    @Override public Uni<CompanyRecord> insert(CompanyRecord company) {
        var row = CompanyEntity.from(company);
        return persistAndFlush(row).map(CompanyEntity::value);
    }
}
