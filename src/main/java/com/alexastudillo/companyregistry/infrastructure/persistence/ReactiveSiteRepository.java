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
                .setParameter("tenant", tenantId).setParameter("company", companyId).setParameter("site", siteId)
                .getResultList()).map(rows -> rows.stream().map(AddressEntity::value).toList());
    }

    @Override
    public Uni<List<ContactRecord>> contacts(UUID tenantId, UUID companyId, UUID siteId) {
        return Panache.getSession().chain(session -> session.createQuery(
                "select c from ContactEntity c, SiteEntity s where c.siteId = s.id "
                        + "and s.tenantId = :tenant and s.companyId = :company and s.id = :site order by c.createdAt, c.id",
                ContactEntity.class)
                .setParameter("tenant", tenantId).setParameter("company", companyId).setParameter("site", siteId)
                .getResultList()).map(rows -> rows.stream().map(ContactEntity::contactRecord).toList());
    }

    private Uni<SiteRecord> requireOwner(UUID tenantId, UUID companyId, UUID siteId) {
        return find(tenantId, companyId, siteId).onItem().ifNull()
                .failWith(() -> new PersistenceFailure(PersistenceFailure.Kind.INVALID_REFERENCE, null));
    }
}
