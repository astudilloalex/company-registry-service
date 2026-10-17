package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import io.smallrye.mutiny.Uni;
import java.util.*;

public final class ReadCompanies {
    public record Page(List<CompanyRegistration.Result> data, String nextCursor) {
        public Page {
            data = List.copyOf(data);
        }
    }

    private final AtomicOperation atomic;
    private final CompanyRepository companies;
    private final PartyReads parties;
    private final CompanyCursors cursors;

    public ReadCompanies(AtomicOperation atomic, CompanyRepository companies, PartyReads parties,
            CompanyCursors cursors) {
        this.atomic = atomic;
        this.companies = companies;
        this.parties = parties;
        this.cursors = cursors;
    }

    public Uni<CompanyRegistration.Result> find(OperationContext context, UUID id) {
        return atomic.read(() -> companies.find(context.tenantId(), id)).chain(row -> {
            if (row == null)
                throw new CompanyFailure(CompanyFailure.Kind.NOT_FOUND);
            return parties.find(context, row.company().partyId()).map(party -> {
                if (party == null || !"LEGAL_ENTITY".equals(party.type()))
                    throw new com.alexastudillo.companyregistry.application.reference.ReferenceFailure(
                            com.alexastudillo.companyregistry.application.reference.ReferenceFailure.Kind.INVALID_CONTRACT);
                return new CompanyRegistration.Result(row, party);
            });
        });
    }

    public Uni<Page> page(OperationContext context, CompanySelection.Request request) {
        var boundary = request.cursor() == null ? null
                : cursors.decode(request.cursor(), context.tenantId(), request.filters());
        return atomic.read(() -> companies.page(context.tenantId(), request.filters(), boundary, request.limit()))
                .chain(rows -> {
                    var selected = rows.subList(0, Math.min(rows.size(), request.limit()));
                    String next = null;
                    if (rows.size() > request.limit()) {
                        var last = selected.getLast();
                        next = cursors.encode(context.tenantId(), request.filters(),
                                new CompanySelection.Boundary(last.audit().createdAt(), last.company().id()));
                    }
                    var nextCursor = next;
                    return parties.findAll(context, selected.stream().map(row -> row.company().partyId()).toList())
                            .map(views -> {
                                var data = new ArrayList<CompanyRegistration.Result>();
                                for (int i = 0; i < selected.size(); i++) {
                                    if (!"LEGAL_ENTITY".equals(views.get(i).type()))
                                        throw new com.alexastudillo.companyregistry.application.reference.ReferenceFailure(
                                                com.alexastudillo.companyregistry.application.reference.ReferenceFailure.Kind.INVALID_CONTRACT);
                                    data.add(new CompanyRegistration.Result(selected.get(i), views.get(i)));
                                }
                                return new Page(data, nextCursor);
                            });
                });
    }
}
