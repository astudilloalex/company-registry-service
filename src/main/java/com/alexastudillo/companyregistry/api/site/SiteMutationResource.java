package com.alexastudillo.companyregistry.api.site;

import static com.alexastudillo.companyregistry.api.site.SiteResponseCode.*;
import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.context.RequestOperationContext;
import com.alexastudillo.companyregistry.api.header.ExpectedVersionParser;
import com.alexastudillo.companyregistry.application.site.MutateSite;
import com.alexastudillo.companyregistry.application.site.SiteMutation;
import com.alexastudillo.companyregistry.domain.model.SiteInformationPatch;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/v1/companies/{companyId}/sites/{siteId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SiteMutationResource {
    private final ResponseManager responses;
    private final RequestOperationContext context;
    private final MutateSite mutations;

    @Inject public SiteMutationResource(ResponseManager responses, RequestOperationContext context, MutateSite mutations) {
        this.responses = responses;
        this.context = context;
        this.mutations = mutations;
    }

    @PATCH
    public Uni<RestResponse<ApiResponse<SiteResponse>>> update(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context HttpHeaders headers, UpdateSiteRequest request) {
        return execute(company, site, headers, SiteMutation.Operation.UPDATE, request == null ? null : request.patch());
    }

    @POST @Path("/activate")
    public Uni<RestResponse<ApiResponse<SiteResponse>>> activate(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context HttpHeaders headers, EmptySiteCommand request) {
        return execute(company, site, headers, SiteMutation.Operation.ACTIVATE, SiteInformationPatch.empty());
    }

    @POST @Path("/deactivate")
    public Uni<RestResponse<ApiResponse<SiteResponse>>> deactivate(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context HttpHeaders headers, EmptySiteCommand request) {
        return execute(company, site, headers, SiteMutation.Operation.DEACTIVATE, SiteInformationPatch.empty());
    }

    @POST @Path("/close")
    public Uni<RestResponse<ApiResponse<SiteResponse>>> close(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context HttpHeaders headers, EmptySiteCommand request) {
        return execute(company, site, headers, SiteMutation.Operation.CLOSE, SiteInformationPatch.empty());
    }

    private Uni<RestResponse<ApiResponse<SiteResponse>>> execute(String company, String site, HttpHeaders headers,
            SiteMutation.Operation operation, SiteInformationPatch patch) {
        return Uni.createFrom().deferred(() -> {
            var business = context.require();
            var companyId = SiteResource.uuid(company, COMPANY_ID_INVALID);
            var siteId = SiteResource.uuid(site, SITE_ID_INVALID);
            var version = expectedVersion(headers);
            var keys = headers.getRequestHeader("Idempotency-Key");
            var key = keys == null || keys.isEmpty() ? null : SiteResource.key(keys);
            if (patch == null) throw SiteApiFailures.error(REQUEST_BODY_REQUIRED);
            return mutations.execute(new SiteMutation.Command(business, companyId, siteId, version, key, operation, patch));
        }).onFailure().transform(SiteApiFailures::translate)
                .map(result -> SiteResponse.from(new com.alexastudillo.companyregistry.application.site.SiteRegistration.Result(
                        result.site(), result.effectivelyOperational())))
                .map(responses::successHttp);
    }

    static long expectedVersion(HttpHeaders headers) {
        var values = headers.getRequestHeader("If-Match");
        if (values == null || values.isEmpty()) throw SiteApiFailures.error(IF_MATCH_REQUIRED);
        if (values.size() != 1) throw SiteApiFailures.error(IF_MATCH_DUPLICATED);
        try {
            return ExpectedVersionParser.parse(values.getFirst()).value();
        } catch (IllegalArgumentException _) {
            throw SiteApiFailures.error(IF_MATCH_INVALID);
        }
    }
}
