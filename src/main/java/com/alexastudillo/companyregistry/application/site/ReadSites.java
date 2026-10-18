package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import io.smallrye.mutiny.Uni;
import java.util.*;

/** Local historical reads; no enrichment or write locks. */
public final class ReadSites {
    public record Page(List<SiteRegistration.Result> data, String nextCursor) {
        public Page { data = List.copyOf(data); }
    }
    private final AtomicOperation atomic;
    private final CompanyRepository companies;
    private final SiteRepository sites;
    private final SiteCursors cursors;
    public ReadSites(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites, SiteCursors cursors) {
        this.atomic = atomic; this.companies = companies; this.sites = sites; this.cursors = cursors;
    }
    public Uni<SiteRegistration.Result> find(OperationContext context, UUID company, UUID site) {
        return atomic.read(() -> parent(context, company).chain(() -> sites.detail(context.tenantId(), company, site))
                .map(row -> {
                    if (row == null) throw new SiteFailure(SiteFailure.Kind.SITE_NOT_FOUND);
                    return SiteRegistration.Result.under(row.site(), row.company());
                }));
    }
    public Uni<Page> page(OperationContext context, UUID company, SiteSelection.Request request) {
        var boundary = request.cursor() == null ? null : cursors.decode(request.cursor(), context.tenantId(), company, request.filters());
        return atomic.read(() -> parent(context, company)
                .chain(() -> sites.page(context.tenantId(), company, request.filters(), boundary, request.limit()))
                .map(rows -> {
                    var selected = rows.subList(0, Math.min(rows.size(), request.limit()));
                    String next = null;
                    if (rows.size() > request.limit()) {
                        var last = selected.getLast().site();
                        next = cursors.encode(context.tenantId(), company, request.filters(),
                                new SiteSelection.Boundary(last.audit().createdAt(), last.site().id()));
                    }
                    return new Page(selected.stream().map(row -> SiteRegistration.Result.under(row.site(), row.company())).toList(), next);
                }));
    }
    private Uni<Void> parent(OperationContext context, UUID company) {
        return companies.find(context.tenantId(), company).onItem().ifNull()
                .failWith(() -> new SiteFailure(SiteFailure.Kind.COMPANY_NOT_FOUND)).replaceWithVoid();
    }
}
