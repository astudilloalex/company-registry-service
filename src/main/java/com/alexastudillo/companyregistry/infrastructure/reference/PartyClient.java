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
    @io.quarkus.rest.client.reactive.jackson.ClientObjectMapper
    static com.fasterxml.jackson.databind.ObjectMapper strictMapper(com.fasterxml.jackson.databind.ObjectMapper defaults) {
        var mapper = defaults.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        for (var type : java.util.List.of(com.fasterxml.jackson.databind.type.LogicalType.Integer,
                com.fasterxml.jackson.databind.type.LogicalType.Boolean)) {
            mapper.coercionConfigFor(type).setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,
                    com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
        for (var shape : java.util.List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) {
            mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                    .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
        return mapper;
    }
    @GET @Path("/{partyId}")
    Uni<RestResponse<RemoteModels.Envelope<RemoteModels.Party>>> find(@PathParam("partyId") UUID id,
            @HeaderParam("Tenant-Id") String tenant, @HeaderParam("User-Id") String actor, @HeaderParam("Process-Id") String process);
    @GET @Path("/{partyId}")
    Uni<RestResponse<RemoteModels.Envelope<PartyReadModels.Detail>>> detail(@PathParam("partyId") UUID id,
            @HeaderParam("Tenant-Id") String tenant, @HeaderParam("User-Id") String actor, @HeaderParam("Process-Id") String process);
    @GET @Path("/{partyId}/identifiers")
    Uni<RestResponse<PartyReadModels.Page>> identifiers(@PathParam("partyId") UUID id,
            @HeaderParam("Tenant-Id") String tenant, @HeaderParam("User-Id") String actor, @HeaderParam("Process-Id") String process,
            @QueryParam("limit") int limit, @QueryParam("cursor") String cursor);
}
