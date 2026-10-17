package com.alexastudillo.companyregistry.application.persistence;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;

/** Children are scoped via their owning Site; their mutation revision belongs to Site. */
public interface SiteRepository {
    Uni<SiteRecord> find(UUID tenantId, UUID companyId, UUID siteId);
    Uni<SiteRecord> insert(SiteRecord site);
    Uni<AddressRecord> insertAddress(UUID tenantId, UUID companyId, AddressRecord address);
    Uni<ContactRecord> insertContact(UUID tenantId, UUID companyId, ContactRecord contact);
    Uni<List<AddressRecord>> addresses(UUID tenantId, UUID companyId, UUID siteId);
    Uni<List<ContactRecord>> contacts(UUID tenantId, UUID companyId, UUID siteId);
}
