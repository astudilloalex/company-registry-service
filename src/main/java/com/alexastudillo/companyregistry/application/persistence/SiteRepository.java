package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;

/** Children are scoped via their owning Site; their mutation revision belongs to Site. */
public interface SiteRepository {
    Uni<SiteRecord> find(UUID tenantId, UUID companyId, UUID siteId);
    Uni<SiteRecord> insert(SiteRecord site);
    /** Scoped diagnostics; SQL uniqueness remains authoritative. */
    Uni<Boolean> codeOccupied(UUID tenantId, UUID companyId, String code);
    Uni<Boolean> headquartersOccupied(UUID tenantId, UUID companyId);
    /** Site and parent state from one qualified statement, never a previous existence lookup. */
    record LocalSite(SiteRecord site, com.alexastudillo.companyregistry.domain.model.Company company) {}
    Uni<LocalSite> detail(UUID tenantId, UUID companyId, UUID siteId);
    Uni<List<LocalSite>> page(UUID tenantId, UUID companyId,
            com.alexastudillo.companyregistry.application.site.SiteSelection.Filters filters,
            com.alexastudillo.companyregistry.application.site.SiteSelection.Boundary boundary, int limit);
    Uni<AddressRecord> insertAddress(UUID tenantId, UUID companyId, AddressRecord address);
    Uni<ContactRecord> insertContact(UUID tenantId, UUID companyId, ContactRecord contact);
    Uni<List<AddressRecord>> addresses(UUID tenantId, UUID companyId, UUID siteId);
    Uni<List<ContactRecord>> contacts(UUID tenantId, UUID companyId, UUID siteId);
}
