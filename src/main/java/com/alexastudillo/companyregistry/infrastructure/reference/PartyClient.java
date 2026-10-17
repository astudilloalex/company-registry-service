package com.alexastudillo.companyregistry.infrastructure.reference;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

/** Nonblocking Party lookup with explicit per-call tenant/caller/correlation context. */
@RegisterRestClient(configKey = "party-registry") @Path("/v1/parties") @Produces(MediaType.APPLICATION_JSON)
public interface PartyClient {
    @GET @Path("/{partyId}")
    Uni<RestResponse<RemoteModels.Envelope<RemoteModels.Party>>> find(@PathParam("partyId") UUID id,
            @HeaderParam("Tenant-Id") String tenant, @HeaderParam("User-Id") String actor, @HeaderParam("Process-Id") String process);
}
