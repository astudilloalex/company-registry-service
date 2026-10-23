package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;

/** Children are scoped via their owning Site; their mutation revision belongs to Site. */
public interface SiteRepository {
    Uni<SiteRecord> find(UUID tenantId, UUID companyId, UUID siteId);
    /** Caller must acquire the qualified Company lock first, within the same transaction. */
    Uni<SiteRecord> findForUpdate(UUID tenantId, UUID companyId, UUID siteId);
    /** Resolve the current legal headquarters under protection, after the Company lock. */
    Uni<SiteRecord> currentHeadquartersForUpdate(UUID tenantId, UUID companyId);
    Uni<SiteRecord> update(SiteRecord site, long expectedVersion);
    Uni<SiteRecord> insert(SiteRecord site);
    /** Scoped diagnostics; SQL uniqueness remains authoritative. */
    Uni<Boolean> codeOccupied(UUID tenantId, UUID companyId, String code);
    Uni<Boolean> headquartersOccupied(UUID tenantId, UUID companyId);
    Uni<Boolean> codeOccupiedByOther(UUID tenantId, UUID companyId, UUID siteId, String code);
    Uni<Boolean> headquartersOccupiedByOther(UUID tenantId, UUID companyId, UUID siteId);
    /** Site and parent state from one qualified statement, never a previous existence lookup. */
    record LocalSite(SiteRecord site, com.alexastudillo.companyregistry.domain.model.Company company) {}
    Uni<LocalSite> detail(UUID tenantId, UUID companyId, UUID siteId);
    Uni<List<LocalSite>> page(UUID tenantId, UUID companyId,
            com.alexastudillo.companyregistry.application.site.SiteSelection.Filters filters,
            com.alexastudillo.companyregistry.application.site.SiteSelection.Boundary boundary, int limit);
    Uni<AddressRecord> insertAddress(UUID tenantId, UUID companyId, AddressRecord address);
    Uni<com.alexastudillo.companyregistry.application.site.AddressMutation.View> addressDetail(
            com.alexastudillo.companyregistry.application.site.AddressScope scope, UUID addressId);
    Uni<List<com.alexastudillo.companyregistry.application.site.AddressMutation.View>> addressPage(
            com.alexastudillo.companyregistry.application.site.AddressScope scope,
            com.alexastudillo.companyregistry.application.site.AddressSelection.Filters filters,
            com.alexastudillo.companyregistry.application.site.AddressSelection.Boundary boundary, int limit);
    /** Called only after the qualified Company/Site locks, within the same acceptance. */
    Uni<AddressRecord> primaryAddress(com.alexastudillo.companyregistry.application.site.AddressScope scope,
            com.alexastudillo.companyregistry.domain.model.AddressType type);
    /** Qualified bulk update; immutable identity and creation audit remain untouched. */
    Uni<AddressRecord> updateAddress(com.alexastudillo.companyregistry.application.site.AddressScope scope, AddressRecord address);
    Uni<com.alexastudillo.companyregistry.application.site.ContactMutation.View> contactDetail(
            com.alexastudillo.companyregistry.application.site.ContactScope scope, UUID contactId);
    Uni<List<com.alexastudillo.companyregistry.application.site.ContactMutation.View>> contactPage(
            com.alexastudillo.companyregistry.application.site.ContactScope scope,
            com.alexastudillo.companyregistry.application.site.ContactSelection.Filters filters,
            com.alexastudillo.companyregistry.application.site.ContactSelection.Boundary boundary, int limit);
    /** Called only after the qualified Company/Site locks, within the same acceptance. */
    Uni<ContactRecord> primaryContact(com.alexastudillo.companyregistry.application.site.ContactScope scope,
            com.alexastudillo.companyregistry.domain.model.ContactType type);
    /** Qualified bulk update; immutable identity and creation audit remain untouched. */
    Uni<ContactRecord> updateContact(com.alexastudillo.companyregistry.application.site.ContactScope scope, ContactRecord contact);
    Uni<Boolean> contactValueOccupiedByOther(com.alexastudillo.companyregistry.application.site.ContactScope scope,
            com.alexastudillo.companyregistry.domain.model.ContactType type, String value, UUID excludedContactId);
    Uni<ContactRecord> insertContact(UUID tenantId, UUID companyId, ContactRecord contact);
    Uni<List<AddressRecord>> addresses(UUID tenantId, UUID companyId, UUID siteId);
    Uni<List<ContactRecord>> contacts(UUID tenantId, UUID companyId, UUID siteId);
}
