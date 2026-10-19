package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

/** Local current reads; terminal aggregate states do not hide nondeleted addresses. */
public final class ReadAddresses {
    public record Page(List<AddressMutation.View> data, String nextCursor) {
        public Page { data = List.copyOf(data); }
    }

    private final AtomicOperation atomic;
    private final CompanyRepository companies;
    private final SiteRepository sites;
    private final AddressCursors cursors;

    public ReadAddresses(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites, AddressCursors cursors) {
        this.atomic = atomic; this.companies = companies; this.sites = sites; this.cursors = cursors;
    }

    public Uni<AddressMutation.View> find(AddressScope scope, UUID addressId) {
        return atomic.read(() -> parents(scope).chain(() -> sites.addressDetail(scope, addressId))
                .onItem().ifNull().failWith(() -> new AddressFailure(AddressFailure.Kind.ADDRESS_NOT_FOUND)));
    }

    public Uni<Page> page(AddressScope scope, AddressSelection.Request request) {
        var boundary = request.cursor() == null ? null : cursors.decode(request.cursor(), scope, request.filters());
        return atomic.read(() -> parents(scope).chain(() -> sites.addressPage(scope, request.filters(), boundary, request.limit()))
                .map(rows -> {
                    var selected = rows.subList(0, Math.min(request.limit(), rows.size()));
                    String next = null;
                    if (rows.size() > request.limit()) {
                        var last = selected.getLast();
                        next = cursors.encode(scope, request.filters(),
                                new AddressSelection.Boundary(last.address().audit().createdAt(), last.addressId()));
                    }
                    return new Page(selected, next);
                }));
    }

    private Uni<Void> parents(AddressScope scope) {
        return companies.find(scope.tenantId(), scope.companyId()).onItem().ifNull()
                .failWith(() -> new AddressFailure(AddressFailure.Kind.COMPANY_NOT_FOUND))
                .chain(() -> sites.find(scope.tenantId(), scope.companyId(), scope.siteId())).onItem().ifNull()
                .failWith(() -> new AddressFailure(AddressFailure.Kind.SITE_NOT_FOUND)).replaceWithVoid();
    }
}
