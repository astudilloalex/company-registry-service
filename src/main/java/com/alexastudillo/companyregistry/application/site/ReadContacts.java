package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

/** Local current reads; terminal aggregate states do not hide nondeleted contacts. */
public final class ReadContacts {
    public record Page(List<ContactMutation.View> data, String nextCursor) {
        public Page { data = List.copyOf(data); }
    }

    private final AtomicOperation atomic;
    private final CompanyRepository companies;
    private final SiteRepository sites;
    private final ContactCursors cursors;

    public ReadContacts(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites, ContactCursors cursors) {
        this.atomic = atomic; this.companies = companies; this.sites = sites; this.cursors = cursors;
    }

    public Uni<ContactMutation.View> find(ContactScope scope, UUID contactId) {
        return atomic.read(() -> parents(scope).chain(() -> sites.contactDetail(scope, contactId))
                .onItem().ifNull().failWith(() -> new ContactFailure(ContactFailure.Kind.CONTACT_NOT_FOUND)));
    }

    public Uni<Page> page(ContactScope scope, ContactSelection.Request request) {
        var boundary = request.cursor() == null ? null : cursors.decode(request.cursor(), scope, request.filters());
        return atomic.read(() -> parents(scope).chain(() -> sites.contactPage(scope, request.filters(), boundary, request.limit()))
                .map(rows -> {
                    var selected = rows.subList(0, Math.min(request.limit(), rows.size()));
                    String next = null;
                    if (rows.size() > request.limit()) {
                        var last = selected.getLast();
                        next = cursors.encode(scope, request.filters(),
                                new ContactSelection.Boundary(last.contact().audit().createdAt(), last.contactId()));
                    }
                    return new Page(selected, next);
                }));
    }

    private Uni<Void> parents(ContactScope scope) {
        return companies.find(scope.tenantId(), scope.companyId()).onItem().ifNull()
                .failWith(() -> new ContactFailure(ContactFailure.Kind.COMPANY_NOT_FOUND))
                .chain(() -> sites.find(scope.tenantId(), scope.companyId(), scope.siteId())).onItem().ifNull()
                .failWith(() -> new ContactFailure(ContactFailure.Kind.SITE_NOT_FOUND)).replaceWithVoid();
    }
}
