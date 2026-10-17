package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.context.RequestOperationContext;
import com.alexastudillo.companyregistry.api.error.*;
import com.alexastudillo.companyregistry.application.company.RegisterCompany;
import com.alexastudillo.companyregistry.application.company.ReadCompanies;
import com.alexastudillo.companyregistry.application.company.CompanySelection;
import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import com.alexastudillo.companyregistry.domain.text.OpaqueValue;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/v1/companies")
@Produces(MediaType.APPLICATION_JSON)
public class CompanyResource {
    private final ResponseManager responses;
    private final RequestOperationContext context;
    private final RegisterCompany registration;
    private final ReadCompanies reads;
    private final com.alexastudillo.companyregistry.application.company.UpdateCompany updates;

    @Inject
    public CompanyResource(ResponseManager responses, RequestOperationContext context,
            RegisterCompany registration, ReadCompanies reads,
            com.alexastudillo.companyregistry.application.company.UpdateCompany updates) {
        this.responses = responses;
        this.context = context;
        this.registration = registration;
        this.reads = reads;
        this.updates = updates;
    }

    @GET
    @Path("/{companyId}")
    public Uni<RestResponse<ApiResponse<CompanyResponse>>> find(@PathParam("companyId") String id) {
        return Uni.createFrom().deferred(() -> reads.find(context.require(), uuid(id)))
                .onFailure().transform(ApiFailures::translate).map(CompanyResponse::from).map(responses::successHttp);
    }

    @GET
    public Uni<RestResponse<ApiResponse<java.util.List<CompanyResponse>>>> list(@Context UriInfo uri) {
        return Uni.createFrom().deferred(() -> {
            var params = uri.getQueryParameters();
            for (var entry : params.entrySet()) {
                if (!java.util.Set.of("code", "partyId", "status", "cursor", "limit").contains(entry.getKey())
                        || entry.getValue().size() != 1
                        || entry.getValue().getFirst() == null || entry.getValue().getFirst().isBlank())
                    throw new IllegalArgumentException("query-invalid");
            }
            var party = params.getFirst("partyId");
            var status = params.getFirst("status");
            var supplied = params.getFirst("limit");
            int limit = 50;
            if (supplied != null) {
                if (!supplied.matches("\\d{1,3}"))
                    throw new IllegalArgumentException("limit-invalid");
                limit = Integer.parseInt(supplied);
            }
            var filters = new CompanySelection.Filters(params.getFirst("code"), party == null ? null : uuid(party),
                    status == null ? null : CompanyStatus.valueOf(status));
            return reads.page(context.require(),
                    new CompanySelection.Request(filters, limit, params.getFirst("cursor")));
        }).onFailure().transform(ApiFailures::translate)
                .map(page -> responses.paginatedHttp(page.data().stream().map(CompanyResponse::from).toList(),
                        new com.alexastudillo.api.response.contract.PaginationMetadata(page.nextCursor(), null, null,
                                null, page.data().size())));
    }

    private static java.util.UUID uuid(String value) {
        if (value == null
                || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("uuid-invalid");
        return java.util.UUID.fromString(value);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<CompanyResponse>>> create(@Context HttpHeaders headers,
            @Valid @NotNull(message = "body-required") CreateCompanyRequest request) {
        return Uni.createFrom().deferred(() -> {
            var keys = headers.getRequestHeader("Idempotency-Key");
            if (keys == null || keys.size() != 1)
                throw new IllegalArgumentException("idempotency-key-invalid");
            var key = OpaqueValue.required(keys.getFirst(), 128);
            return registration.execute(request.command(context.require(), key));
        }).onFailure().transform(ApiFailures::translate).map(CompanyResponse::from)
                .map(data -> responses.customHttp(CompanyResponseCode.CREATED, data));
    }

    @PATCH
    @Path("/{companyId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<CompanyOperationalResponse>>> update(@PathParam("companyId") String id,
            @Context HttpHeaders headers, @Valid @NotNull(message = "body-required") UpdateCompanyRequest request) {
        return Uni.createFrom().deferred(() -> {
            var business = context.require();
            var companyId = uuid(id);
            var versions = headers.getRequestHeader("If-Match");
            if (versions == null || versions.size() != 1)
                throw new IllegalArgumentException("if-match-invalid");
            var expected = com.alexastudillo.companyregistry.api.header.ExpectedVersionParser
                    .parse(versions.getFirst());
            var keys = headers.getRequestHeader("Idempotency-Key");
            String key = null;
            if (keys != null && !keys.isEmpty()) {
                if (keys.size() != 1)
                    throw new IllegalArgumentException("idempotency-key-invalid");
                key = OpaqueValue.required(keys.getFirst(), 128);
            }
            return updates.execute(new com.alexastudillo.companyregistry.application.company.CompanyUpdate.Command(
                    business, companyId, expected.value(), key, request.patch()));
        }).onFailure().transform(ApiFailures::translate).map(CompanyOperationalResponse::from)
                .map(responses::successHttp);
    }

}
