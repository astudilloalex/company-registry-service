package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ReactiveSiteRepository implements SiteRepository, PanacheRepositoryBase<SiteEntity, UUID> {
    // Scalar parent values bypass the first-level entity already loaded for existence.
    private static final String LOCAL = "select s, c.id, c.tenantId, c.partyId, c.status, c.version from SiteEntity s, CompanyEntity c where s.tenantId = :tenant "
            + "and s.companyId = :company and c.tenantId = s.tenantId and c.id = s.companyId";
    private static final String PARAM_TENANT = "tenant";
    private static final String PARAM_COMPANY = "company";
    private static final String PARAM_SITE = "site";

    @Override public Uni<LocalSite> detail(UUID tenant, UUID company, UUID site) {
        return Panache.getSession().chain(session -> session.createQuery(LOCAL + " and s.id = :site", Object[].class)
                .setParameter(PARAM_TENANT, tenant).setParameter(PARAM_COMPANY, company).setParameter(PARAM_SITE, site)
                .getSingleResultOrNull()).map(row -> row == null ? null : local(row));
    }

    @Override public Uni<List<LocalSite>> page(UUID tenant, UUID company,
            com.alexastudillo.companyregistry.application.site.SiteSelection.Filters filters,
            com.alexastudillo.companyregistry.application.site.SiteSelection.Boundary boundary, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("site-page-limit-invalid");
        var hql = new StringBuilder(LOCAL);
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put(PARAM_TENANT, tenant); parameters.put(PARAM_COMPANY, company);
        if (filters.code() != null) { hql.append(" and s.code = :code"); parameters.put("code", filters.code()); }
        if (filters.siteType() != null) { hql.append(" and s.type = :type"); parameters.put("type", filters.siteType()); }
        if (filters.status() != null) { hql.append(" and s.status = :status"); parameters.put("status", filters.status()); }
        if (boundary != null) {
            hql.append(" and (s.createdAt < :created or (s.createdAt = :created and s.id < :id))");
            parameters.put("created", boundary.createdAt()); parameters.put("id", boundary.id());
        }
        hql.append(" order by s.createdAt desc, s.id desc");
        return Panache.getSession().chain(session -> {
            var query = session.createQuery(hql.toString(), Object[].class);
            parameters.forEach(query::setParameter);
            return query.setMaxResults(limit + 1).getResultList();
        }).map(rows -> rows.stream().map(ReactiveSiteRepository::local).toList());
    }

    private static LocalSite local(Object[] row) {
        var company = new com.alexastudillo.companyregistry.domain.model.Company((UUID)row[1], (UUID)row[2], (UUID)row[3],
                (com.alexastudillo.companyregistry.domain.model.CompanyStatus)row[4],
                new com.alexastudillo.companyregistry.domain.model.AggregateVersion(((Number)row[5]).longValue()));
        return new LocalSite(((SiteEntity)row[0]).value(), company);
    }

    @Override
    public Uni<SiteRecord> find(UUID tenantId, UUID companyId, UUID siteId) {
        return find("tenantId = ?1 and companyId = ?2 and id = ?3", tenantId, companyId, siteId)
                .firstResult().map(row -> row == null ? null : row.value());
    }

    @Override
    public Uni<SiteRecord> insert(SiteRecord site) {
        return persistAndFlush(SiteEntity.from(site)).map(SiteEntity::value);
    }

    @Override
    public Uni<SiteRecord> findForUpdate(UUID tenantId, UUID companyId, UUID siteId) {
        return find("tenantId = ?1 and companyId = ?2 and id = ?3", tenantId, companyId, siteId)
                .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).firstResult()
                .map(row -> row == null ? null : row.value());
    }

    @Override
    public Uni<SiteRecord> update(SiteRecord row, long expectedVersion) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                || row.site().version().value() != expectedVersion + 1) {
            throw new IllegalArgumentException("site-update-version-invalid");
        }
        // A single bulk update leaves the managed entity untouched: @Version cannot increment twice.
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put(PARAM_TENANT, row.site().tenantId());
        parameters.put(PARAM_COMPANY, row.site().companyId());
        parameters.put(PARAM_SITE, row.site().id());
        parameters.put("expected", expectedVersion);
        parameters.put("code", row.code());
        parameters.put("name", row.name());
        parameters.put("type", row.site().type());
        parameters.put("status", row.site().status());
        parameters.put("at", row.audit().updatedAt());
        parameters.put("actor", row.audit().updatedBy());
        return update("code=:code, name=:name, type=:type, status=:status, updatedAt=:at, updatedBy=:actor, "
                + "version=version+1 where tenantId=:tenant and companyId=:company and id=:site and version=:expected", parameters)
                .chain(count -> count == 1 ? Uni.createFrom().item(row)
                        : Uni.createFrom().failure(new PersistenceFailure(PersistenceFailure.Kind.STALE_VERSION, null)));
    }

    @Override
    public Uni<Boolean> codeOccupiedByOther(UUID tenantId, UUID companyId, UUID siteId, String code) {
        return count("tenantId = ?1 and companyId = ?2 and id <> ?3 and code = ?4", tenantId, companyId, siteId, code)
                .map(count -> count > 0);
    }

    @Override
    public Uni<Boolean> headquartersOccupiedByOther(UUID tenantId, UUID companyId, UUID siteId) {
        return count("tenantId = ?1 and companyId = ?2 and id <> ?3 and type = ?4 and status <> ?5",
                tenantId, companyId, siteId, com.alexastudillo.companyregistry.domain.model.SiteType.HEADQUARTERS,
                com.alexastudillo.companyregistry.domain.model.SiteStatus.CLOSED).map(count -> count > 0);
    }

    @Override public Uni<Boolean> codeOccupied(UUID tenantId, UUID companyId, String code) {
        return count("tenantId = ?1 and companyId = ?2 and code = ?3", tenantId, companyId, code).map(n -> n > 0);
    }

    @Override public Uni<Boolean> headquartersOccupied(UUID tenantId, UUID companyId) {
        return count("tenantId = ?1 and companyId = ?2 and type = ?3 and status <> ?4", tenantId, companyId,
                com.alexastudillo.companyregistry.domain.model.SiteType.HEADQUARTERS,
                com.alexastudillo.companyregistry.domain.model.SiteStatus.CLOSED).map(n -> n > 0);
    }

    @Override
    public Uni<AddressRecord> insertAddress(UUID tenantId, UUID companyId, AddressRecord address) {
        return requireOwner(tenantId, companyId, address.siteId()).chain(Panache::getSession)
                .chain(session -> {
                    var row = AddressEntity.from(address);
                    return session.persist(row).call(session::flush).replaceWith(row::value);
                });
    }

    @Override
    public Uni<ContactRecord> insertContact(UUID tenantId, UUID companyId, ContactRecord contact) {
        return requireOwner(tenantId, companyId, contact.siteId()).chain(Panache::getSession)
                .chain(session -> {
                    var row = ContactEntity.from(contact);
                    return session.persist(row).call(session::flush).replaceWith(row::contactRecord);
                });
    }

    @Override
    public Uni<List<AddressRecord>> addresses(UUID tenantId, UUID companyId, UUID siteId) {
        return Panache.getSession().chain(session -> session.createQuery(
                "select a from AddressEntity a, SiteEntity s where a.siteId = s.id "
                        + "and s.tenantId = :tenant and s.companyId = :company and s.id = :site order by a.createdAt, a.id",
                AddressEntity.class)
                .setParameter(PARAM_TENANT, tenantId).setParameter(PARAM_COMPANY, companyId).setParameter(PARAM_SITE, siteId)
                .getResultList()).map(rows -> rows.stream().map(AddressEntity::value).toList());
    }

    @Override
    public Uni<List<ContactRecord>> contacts(UUID tenantId, UUID companyId, UUID siteId) {
        return Panache.getSession().chain(session -> session.createQuery(
                "select c from ContactEntity c, SiteEntity s where c.siteId = s.id "
                        + "and s.tenantId = :tenant and s.companyId = :company and s.id = :site order by c.createdAt, c.id",
                ContactEntity.class)
                .setParameter(PARAM_TENANT, tenantId).setParameter(PARAM_COMPANY, companyId).setParameter(PARAM_SITE, siteId)
                .getResultList()).map(rows -> rows.stream().map(ContactEntity::contactRecord).toList());
    }

    private Uni<SiteRecord> requireOwner(UUID tenantId, UUID companyId, UUID siteId) {
        return find(tenantId, companyId, siteId).onItem().ifNull()
                .failWith(() -> new PersistenceFailure(PersistenceFailure.Kind.INVALID_REFERENCE, null));
    }
}
