package com.alexastudillo.companyregistry.api.verification;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.context.RequestOperationContext;
import com.alexastudillo.companyregistry.api.error.*;
import com.alexastudillo.companyregistry.application.reference.*;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;

/** Actual client calls from the verification process to independently hosted URL stubs. */
@Path("/v1/_verification/references") @Produces(MediaType.APPLICATION_JSON)
public class ReferenceProbeResource {
    @Inject PartyReferences parties;
    @Inject CountryReferences countries;
    @Inject DivisionReferences divisions;
    @Inject RequestOperationContext context;
    @Inject ResponseManager responses;
    @GET @Path("/party/{id}")
    public Uni<RestResponse<ApiResponse<ReferenceProbeResponse>>> party(@PathParam("id") UUID id) {
        return finish(parties.find(context.require(), id).map(value -> value == null ? null :
                new ReferenceProbeResponse(value.id(), value.kind().name(), value.state().name(), null, null, value.active())));
    }
    @GET @Path("/country/{code}")
    public Uni<RestResponse<ApiResponse<ReferenceProbeResponse>>> country(@PathParam("code") String code) {
        return finish(countries.find(context.require(), code).map(value -> value == null ? null :
                new ReferenceProbeResponse(value.id(), null, value.state().name(), null, value.alpha2Code(), value.active())));
    }
    @GET @Path("/division/{country}/{code}")
    public Uni<RestResponse<ApiResponse<ReferenceProbeResponse>>> division(@PathParam("country") String country, @PathParam("code") String code) {
        return finish(divisions.find(context.require(), country, code).map(value -> value == null ? null :
                new ReferenceProbeResponse(value.id(), null, value.state().name(), value.countryId(), value.canonicalCode(), value.active())));
    }
    private Uni<RestResponse<ApiResponse<ReferenceProbeResponse>>> finish(Uni<ReferenceProbeResponse> result) {
        return result.onItem().ifNull().failWith(() -> new ApiResponseException(CompanyResponseCode.REFERENCE_NOT_FOUND))
                .onFailure(ReferenceFailure.class).transform(ApiFailures::translate).map(responses::successHttp);
    }
}
