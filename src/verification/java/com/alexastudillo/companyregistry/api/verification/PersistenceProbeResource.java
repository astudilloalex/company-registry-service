package com.alexastudillo.companyregistry.api.verification;

import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.application.persistence.Identifiers;
import com.alexastudillo.companyregistry.application.verification.PersistenceVerification;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

/** Test-variant operations for external native persistence and runtime UUID verification. */
@Path("/v1/_verification") @Produces(MediaType.APPLICATION_JSON)
public class PersistenceProbeResource {
    @Inject PersistenceVerification verification;
    @Inject Identifiers ids;
    @Inject ResponseManager responses;
    @POST @Path("/persistence/{rollback}")
    public Uni<RestResponse<ApiResponse<PersistenceProbeResponse>>> persistence(@PathParam("rollback") boolean rollback) {
        return verification.verify(rollback).map(result -> new PersistenceProbeResponse(result.companyId(), result.siteId(), result.eventId(), result.rolledBack()))
                .map(responses::successHttp);
    }
    @GET @Path("/uuid")
    public Uni<RestResponse<ApiResponse<ProbeResponse>>> uuid() {
        return Uni.createFrom().item(() -> new ProbeResponse(ids.next().toString())).map(responses::successHttp);
    }
}
