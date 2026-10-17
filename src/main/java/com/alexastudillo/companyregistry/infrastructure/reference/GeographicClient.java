package com.alexastudillo.companyregistry.infrastructure.reference;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

/** Provider-supported context only: never invent company-id from a tenant identity. */
@RegisterRestClient(configKey = "geographic-reference") @Path("/api/v1/countries") @Produces(MediaType.APPLICATION_JSON)
public interface GeographicClient {
    @GET @Path("/by-alpha2/{code}")
    Uni<RestResponse<RemoteModels.Envelope<RemoteModels.Country>>> country(@PathParam("code") String code,
            @HeaderParam("user-id") String actor, @HeaderParam("process-id") String process);

    @GET @Path("/{countryId}/administrative-divisions/by-canonical-code/{code}")
    Uni<RestResponse<RemoteModels.Envelope<RemoteModels.Division>>> division(@PathParam("countryId") java.util.UUID countryId,
            @PathParam("code") String code, @HeaderParam("user-id") String actor, @HeaderParam("process-id") String process);
}
