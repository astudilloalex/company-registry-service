package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.site.AddressMutation;
import com.alexastudillo.companyregistry.application.site.AddressScope;
import com.alexastudillo.companyregistry.application.site.AddressSelection;
import com.alexastudillo.companyregistry.domain.model.AddressType;
import com.alexastudillo.companyregistry.domain.model.ContactType;
import com.alexastudillo.companyregistry.application.site.ContactMutation;
import com.alexastudillo.companyregistry.application.site.ContactScope;
import com.alexastudillo.companyregistry.application.site.ContactSelection;
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
    private static final String PARAM_ADDRESS = "address";
    private static final String PARAM_TYPE = "type";
    private static final String PARAM_CONTACT = "contact";
    private static final String PARAM_CREATED = "created";
    private static final String PARAM_PRIMARY = "primary";
    private static final String PARAM_ACTOR = "actor";
    private static final String PARAM_AT = "at";
    private static final String CONTACTS = "select a, s.version from ContactEntity a, SiteEntity s, CompanyEntity c "
            + "where a.siteId = s.id and c.id = s.companyId and c.tenantId = s.tenantId "
            + "and s.tenantId = :tenant and s.companyId = :company and s.id = :site and a.deletedAt is null";
    private static final String ADDRESSES = "select a, s.version from AddressEntity a, SiteEntity s, CompanyEntity c "
            + "where a.siteId = s.id and c.id = s.companyId and c.tenantId = s.tenantId "
            + "and s.tenantId = :tenant and s.companyId = :company and s.id = :site and a.deletedAt is null";

    @Override
    public Uni<AddressMutation.View> addressDetail(AddressScope scope, UUID addressId) {
        return Panache.getSession().chain(session -> {
            var query = session.createQuery(ADDRESSES + " and a.id = :address", Object[].class);
            addressParameters(scope).forEach(query::setParameter);
            return query.setParameter(PARAM_ADDRESS, addressId).getSingleResultOrNull();
        }).map(row -> row == null ? null : addressView(scope, row));
    }

    @Override
    public Uni<List<AddressMutation.View>> addressPage(AddressScope scope, AddressSelection.Filters filters,
            AddressSelection.Boundary boundary, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("address-page-limit-invalid");
        var hql = new StringBuilder(ADDRESSES);
        var parameters = addressParameters(scope);
        if (filters.addressType() != null) {
            hql.append(" and a.type = :type"); parameters.put(PARAM_TYPE, filters.addressType());
        }
        if (filters.isPrimary() != null) {
            hql.append(" and a.primaryValue = :primary"); parameters.put(PARAM_PRIMARY, filters.isPrimary());
        }
        if (boundary != null) {
            hql.append(" and (a.createdAt < :created or (a.createdAt = :created and a.id < :address))");
            parameters.put(PARAM_CREATED, boundary.createdAt()); parameters.put(PARAM_ADDRESS, boundary.addressId());
        }
        hql.append(" order by a.createdAt desc, a.id desc");
        return Panache.getSession().chain(session -> {
            var query = session.createQuery(hql.toString(), Object[].class);
            parameters.forEach(query::setParameter);
            return query.setMaxResults(limit + 1).getResultList();
        }).map(rows -> rows.stream().map(row -> addressView(scope, row)).toList());
    }

    @Override
    public Uni<AddressRecord> primaryAddress(AddressScope scope, AddressType type) {
        return addressPage(scope, new AddressSelection.Filters(type, true), null, 1).map(rows -> {
            if (rows.size() > 1) throw new IllegalStateException("address-primary-integrity-invalid");
            return rows.isEmpty() ? null : rows.getFirst().address();
        });
    }

    @Override
    public Uni<AddressRecord> updateAddress(AddressScope scope, AddressRecord row) {
        if (!scope.siteId().equals(row.siteId())) throw new IllegalArgumentException("address-site-mismatch");
        var parameters = addressParameters(scope);
        parameters.put(PARAM_ADDRESS, row.id()); parameters.put(PARAM_TYPE, row.type());
        parameters.put("country", row.countryCode()); parameters.put("division", row.administrativeDivisionCode());
        parameters.put("line1", row.line1()); parameters.put("line2", row.line2());
        parameters.put("house", row.houseNumber()); parameters.put("postal", row.postalCode());
        parameters.put("latitude", row.latitude()); parameters.put("longitude", row.longitude());
        parameters.put(PARAM_PRIMARY, row.primary()); parameters.put(PARAM_AT, row.audit().updatedAt());
        parameters.put(PARAM_ACTOR, row.audit().updatedBy()); parameters.put("deletedAt", row.deletedAt());
        parameters.put("deletedBy", row.deletedBy());
        return Panache.getSession().chain(session -> {
            var query = session.createMutationQuery("update AddressEntity a set type=:type, countryCode=:country, "
                    + "division=:division, line1=:line1, line2=:line2, houseNumber=:house, postalCode=:postal, "
                    + "latitude=:latitude, longitude=:longitude, primaryValue=:primary, updatedAt=:at, updatedBy=:actor, "
                    + "deletedAt=:deletedAt, deletedBy=:deletedBy where a.id=:address and a.siteId=:site and a.deletedAt is null "
                    + "and exists (select 1 from SiteEntity s, CompanyEntity c where s.id=a.siteId "
                    + "and s.tenantId=:tenant and s.companyId=:company and c.id=s.companyId and c.tenantId=s.tenantId)");
            parameters.forEach(query::setParameter);
            return query.executeUpdate();
        }).chain(count -> count == 1 ? Uni.createFrom().item(row)
                : Uni.createFrom().failure(new PersistenceFailure(PersistenceFailure.Kind.INVALID_REFERENCE, null)));
    }

    private static java.util.HashMap<String, Object> addressParameters(AddressScope scope) {
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put(PARAM_TENANT, scope.tenantId());
        parameters.put(PARAM_COMPANY, scope.companyId());
        parameters.put(PARAM_SITE, scope.siteId());
        return parameters;
    }

    private static AddressMutation.View addressView(AddressScope scope, Object[] row) {
        return new AddressMutation.View(scope, ((AddressEntity) row[0]).value(), ((Number) row[1]).longValue());
    }

    @Override
    public Uni<ContactMutation.View> contactDetail(ContactScope scope, UUID contactId) {
        return Panache.getSession().chain(session -> {
            var query = session.createQuery(CONTACTS + " and a.id = :contact", Object[].class);
            contactParameters(scope).forEach(query::setParameter);
            return query.setParameter(PARAM_CONTACT, contactId).getSingleResultOrNull();
        }).map(row -> row == null ? null : contactView(scope, row));
    }

    @Override
    public Uni<List<ContactMutation.View>> contactPage(ContactScope scope, ContactSelection.Filters filters,
            ContactSelection.Boundary boundary, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("contact-page-limit-invalid");
        var hql = new StringBuilder(CONTACTS);
        var parameters = contactParameters(scope);
        if (filters.contactType() != null) {
            hql.append(" and a.type = :type"); parameters.put(PARAM_TYPE, filters.contactType());
        }
        if (filters.isPrimary() != null) {
            hql.append(" and a.primaryValue = :primary"); parameters.put(PARAM_PRIMARY, filters.isPrimary());
        }
        if (boundary != null) {
            hql.append(" and (a.createdAt < :created or (a.createdAt = :created and a.id < :contact))");
            parameters.put(PARAM_CREATED, boundary.createdAt()); parameters.put(PARAM_CONTACT, boundary.contactId());
        }
        hql.append(" order by a.createdAt desc, a.id desc");
        return Panache.getSession().chain(session -> {
            var query = session.createQuery(hql.toString(), Object[].class);
            parameters.forEach(query::setParameter);
            return query.setMaxResults(limit + 1).getResultList();
        }).map(rows -> rows.stream().map(row -> contactView(scope, row)).toList());
    }

    @Override
    public Uni<ContactRecord> primaryContact(ContactScope scope, ContactType type) {
        return contactPage(scope, new ContactSelection.Filters(type, true), null, 1).map(rows -> {
            if (rows.size() > 1) throw new IllegalStateException("contact-primary-integrity-invalid");
            return rows.isEmpty() ? null : rows.getFirst().contact();
        });
    }

    @Override
    public Uni<ContactRecord> updateContact(ContactScope scope, ContactRecord row) {
        if (!scope.siteId().equals(row.siteId())) throw new IllegalArgumentException("contact-site-mismatch");
        var parameters = contactParameters(scope);
        parameters.put(PARAM_CONTACT, row.id()); parameters.put(PARAM_TYPE, row.type());
        parameters.put("value", row.value()); parameters.put("label", row.label());
        parameters.put(PARAM_PRIMARY, row.primary()); parameters.put(PARAM_AT, row.audit().updatedAt());
        parameters.put(PARAM_ACTOR, row.audit().updatedBy()); parameters.put("deletedAt", row.deletedAt());
        parameters.put("deletedBy", row.deletedBy());
        return Panache.getSession().chain(session -> {
            var query = session.createMutationQuery("update ContactEntity a set type=:type, value=:value, label=:label, "
                    + "primaryValue=:primary, updatedAt=:at, updatedBy=:actor, deletedAt=:deletedAt, deletedBy=:deletedBy "
                    + "where a.id=:contact and a.siteId=:site and a.deletedAt is null "
                    + "and exists (select 1 from SiteEntity s, CompanyEntity c where s.id=a.siteId "
                    + "and s.tenantId=:tenant and s.companyId=:company and c.id=s.companyId and c.tenantId=s.tenantId)");
            parameters.forEach(query::setParameter);
            return query.executeUpdate();
        }).chain(count -> count == 1 ? Uni.createFrom().item(row)
                : Uni.createFrom().failure(new PersistenceFailure(PersistenceFailure.Kind.INVALID_REFERENCE, null)));
    }

    private static java.util.HashMap<String, Object> contactParameters(ContactScope scope) {
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put(PARAM_TENANT, scope.tenantId());
        parameters.put(PARAM_COMPANY, scope.companyId());
        parameters.put(PARAM_SITE, scope.siteId());
        return parameters;
    }

    private static ContactMutation.View contactView(ContactScope scope, Object[] row) {
        return new ContactMutation.View(scope, ((ContactEntity) row[0]).contactRecord(), ((Number) row[1]).longValue());
    }

    @Override
    public Uni<Boolean> contactValueOccupiedByOther(ContactScope scope, ContactType type, String value, UUID excludedContactId) {
        var parameters = contactParameters(scope);
        parameters.put(PARAM_TYPE, type);
        parameters.put("value", value);
        String exclusion = excludedContactId == null ? "" : " and a.id <> :contact";
        if (excludedContactId != null) parameters.put(PARAM_CONTACT, excludedContactId);
        return Panache.getSession().chain(session -> {
            var query = session.createQuery(CONTACTS + " and a.type=:type and a.value=:value" + exclusion, Object[].class);
            parameters.forEach(query::setParameter);
            return query.setMaxResults(1).getResultList();
        }).map(rows -> !rows.isEmpty());
    }

    @Override
    public Uni<SiteRecord> currentHeadquartersForUpdate(UUID tenantId, UUID companyId) {
        return find("tenantId = ?1 and companyId = ?2 and type = ?3 and status <> ?4", tenantId, companyId,
                com.alexastudillo.companyregistry.domain.model.SiteType.HEADQUARTERS,
                com.alexastudillo.companyregistry.domain.model.SiteStatus.CLOSED)
                .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).list().map(rows -> {
                    if (rows.size() > 1) throw new IllegalStateException("headquarters-integrity-invalid");
                    return rows.isEmpty() ? null : rows.getFirst().value();
                });
    }

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
        if (filters.siteType() != null) { hql.append(" and s.type = :type"); parameters.put(PARAM_TYPE, filters.siteType()); }
        if (filters.status() != null) { hql.append(" and s.status = :status"); parameters.put("status", filters.status()); }
        if (boundary != null) {
            hql.append(" and (s.createdAt < :created or (s.createdAt = :created and s.id < :id))");
            parameters.put(PARAM_CREATED, boundary.createdAt()); parameters.put("id", boundary.id());
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
        parameters.put(PARAM_TYPE, row.site().type());
        parameters.put("status", row.site().status());
        parameters.put(PARAM_AT, row.audit().updatedAt());
        parameters.put(PARAM_ACTOR, row.audit().updatedBy());
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
