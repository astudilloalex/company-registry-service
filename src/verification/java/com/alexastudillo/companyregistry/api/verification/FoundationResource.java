package com.alexastudillo.companyregistry.api.verification;

import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.error.ApiFailures;
import com.alexastudillo.companyregistry.api.context.RequestOperationContext;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/v1/_verification") @Produces(MediaType.APPLICATION_JSON)
public class FoundationResource {
    @Inject ResponseManager responses;
    @Inject RequestOperationContext context;

    @GET @Path("/success")
    public Uni<RestResponse<ApiResponse<ProbeResponse>>> success() {
        return Uni.createFrom().item("ready").map(ProbeResponse::new).map(responses::successHttp);
    }
    @GET @Path("/known-failure")
    public Uni<RestResponse<ApiResponse<ProbeResponse>>> knownFailure() {
        return Uni.createFrom().<ProbeResponse>failure(new PersistenceFailure(PersistenceFailure.Kind.INVALID_REFERENCE,
                        new IllegalStateException("private-provider-payload")))
                .onFailure(PersistenceFailure.class).transform(ApiFailures::translate).map(responses::successHttp);
    }
    @GET @Path("/unexpected-failure")
    public Uni<RestResponse<ApiResponse<ProbeResponse>>> unexpectedFailure() {
        return Uni.createFrom().failure(new IllegalStateException("SELECT secret FROM internal_table private-provider-payload"));
    }
    @GET @Path("/context")
    public Uni<RestResponse<ApiResponse<ProbeResponse>>> context() {
        var original = context.require();
        return Uni.createFrom().item(original).onItem().delayIt().by(java.time.Duration.ofMillis(20))
                .map(current -> {
                    if (!current.equals(context.require()) || !current.processId().equals(org.jboss.logging.MDC.get("processId"))) {
                        throw new IllegalStateException("request-context-leak");
                    }
                    return new ProbeResponse(current.processId());
                }).map(responses::successHttp);
    }
    @POST @Path("/echo") @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<ProbeResponse>>> echo(
            @jakarta.validation.Valid @jakarta.validation.constraints.NotNull(message = "body-required") ProbeRequest request) {
        return Uni.createFrom().item(request.name()).map(ProbeResponse::new).map(responses::successHttp);
    }
}
