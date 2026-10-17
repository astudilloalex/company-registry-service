package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class ReactiveCompanyRepository implements CompanyRepository, PanacheRepositoryBase<CompanyEntity, UUID> {
    @Override
    public Uni<CompanyRecord> findForUpdate(UUID tenantId, UUID companyId) {
        return find("tenantId = ?1 and id = ?2", tenantId, companyId)
                .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).firstResult()
                .map(row -> row == null ? null : row.value());
    }

    @Override
    public Uni<CompanyRecord> update(CompanyRecord row, long expectedVersion) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE || row.company().version().value() != expectedVersion + 1)
            throw new IllegalArgumentException("update-version-invalid");
        // Bulk HQL changes no managed entity; @Version cannot add a second increment.
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("tenant", row.company().tenantId()); parameters.put("id", row.company().id());
        parameters.put("expected", expectedVersion); parameters.put("code", row.code());
        parameters.put("name", row.operatingName()); parameters.put("description", row.description());
        parameters.put("logo", row.logoUrl()); parameters.put("at", row.audit().updatedAt());
        parameters.put("actor", row.audit().updatedBy());
        return update("code=:code, operatingName=:name, description=:description, logoUrl=:logo, updatedAt=:at, updatedBy=:actor, "
                + "version=version+1 where tenantId=:tenant and id=:id and version=:expected", parameters)
                .chain(count -> count == 1 ? Uni.createFrom().item(row)
                        : Uni.createFrom().failure(new com.alexastudillo.companyregistry.application.persistence.PersistenceFailure(
                                com.alexastudillo.companyregistry.application.persistence.PersistenceFailure.Kind.STALE_VERSION, null)));
    }

    @Override
    public Uni<java.util.List<CompanyRecord>> page(UUID tenantId,
            com.alexastudillo.companyregistry.application.company.CompanySelection.Filters filters,
            com.alexastudillo.companyregistry.application.company.CompanySelection.Boundary boundary, int limit) {
        if (limit < 1 || limit > 200)
            throw new IllegalArgumentException("limit-invalid");
        var query = new StringBuilder("tenantId = :tenant");
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("tenant", tenantId);
        if (filters.code() != null) {
            query.append(" and code = :code");
            parameters.put("code", filters.code());
        }
        if (filters.partyId() != null) {
            query.append(" and partyId = :party");
            parameters.put("party", filters.partyId());
        }
        if (filters.status() != null) {
            query.append(" and status = :status");
            parameters.put("status", filters.status());
        }
        if (boundary != null) {
            query.append(" and (createdAt < :created or (createdAt = :created and id < :id))");
            parameters.put("created", boundary.createdAt());
            parameters.put("id", boundary.id());
        }
        query.append(" order by createdAt desc, id desc");
        return find(query.toString(), parameters).range(0, limit).list()
                .map(rows -> rows.stream().map(CompanyEntity::value).toList());
    }

    @Override
    public Uni<Boolean> occupied(UUID tenantId, String code, UUID partyId) {
        return count("tenantId = ?1 and (code = ?2 or partyId = ?3)", tenantId, code, partyId).map(count -> count > 0);
    }

    @Override
    public Uni<CompanyRecord> find(UUID tenantId, UUID companyId) {
        return find("tenantId = ?1 and id = ?2", tenantId, companyId).firstResult()
                .map(row -> row == null ? null : row.value());
    }

    @Override
    public Uni<CompanyRecord> insert(CompanyRecord company) {
        var row = CompanyEntity.from(company);
        return persistAndFlush(row).map(CompanyEntity::value);
    }
}
