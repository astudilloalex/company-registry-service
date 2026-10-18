package com.alexastudillo.companyregistry.api.company;

import static com.alexastudillo.companyregistry.api.company.CompanyLifecycleResponseCode.*;

import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.context.RequestOperationContext;
import com.alexastudillo.companyregistry.api.header.ExpectedVersionParser;
import com.alexastudillo.companyregistry.application.company.CompanyLifecycle;
import com.alexastudillo.companyregistry.application.company.TransitionCompany;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/v1/companies/{companyId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompanyLifecycleResource {
    private final ResponseManager responses;
    private final RequestOperationContext context;
    private final TransitionCompany lifecycle;

    @Inject public CompanyLifecycleResource(ResponseManager responses, RequestOperationContext context, TransitionCompany lifecycle) {
        this.responses = responses;
        this.context = context;
        this.lifecycle = lifecycle;
    }

    @POST @Path("/activate")
    public Uni<RestResponse<ApiResponse<CompanyOperationalResponse>>> activate(@PathParam("companyId") String company,
            @Context HttpHeaders headers, EmptyCompanyCommand request) {
        return execute(company, headers, CompanyLifecycle.Operation.ACTIVATE);
    }

    @POST @Path("/deactivate")
    public Uni<RestResponse<ApiResponse<CompanyOperationalResponse>>> deactivate(@PathParam("companyId") String company,
            @Context HttpHeaders headers, EmptyCompanyCommand request) {
        return execute(company, headers, CompanyLifecycle.Operation.DEACTIVATE);
    }

    @POST @Path("/archive")
    public Uni<RestResponse<ApiResponse<CompanyOperationalResponse>>> archive(@PathParam("companyId") String company,
            @Context HttpHeaders headers, EmptyCompanyCommand request) {
        return execute(company, headers, CompanyLifecycle.Operation.ARCHIVE);
    }

    private Uni<RestResponse<ApiResponse<CompanyOperationalResponse>>> execute(String company, HttpHeaders headers,
            CompanyLifecycle.Operation operation) {
        return Uni.createFrom().deferred(() -> {
            var business = context.require();
            var id = uuid(company);
            var version = expectedVersion(headers.getRequestHeader("If-Match"));
            var key = key(headers.getRequestHeader("Idempotency-Key"));
            return lifecycle.execute(new CompanyLifecycle.Command(business, id, version, key, operation));
        }).onFailure().transform(CompanyLifecycleApiFailures::translate)
                .map(CompanyOperationalResponse::from).map(responses::successHttp);
    }

    private static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw CompanyLifecycleApiFailures.error(COMPANY_ID_INVALID);
        }
        return UUID.fromString(value);
    }

    private static long expectedVersion(List<String> values) {
        if (values == null || values.isEmpty()) throw CompanyLifecycleApiFailures.error(IF_MATCH_REQUIRED);
        if (values.size() != 1) throw CompanyLifecycleApiFailures.error(IF_MATCH_DUPLICATED);
        try {
            return ExpectedVersionParser.parse(values.getFirst()).value();
        } catch (IllegalArgumentException _) {
            throw CompanyLifecycleApiFailures.error(IF_MATCH_INVALID);
        }
    }

    private static String key(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1) throw CompanyLifecycleApiFailures.error(IDEMPOTENCY_KEY_DUPLICATED);
        var key = values.getFirst();
        if (key == null || key.isBlank() || key.codePoints().anyMatch(Character::isISOControl)) {
            throw CompanyLifecycleApiFailures.error(IDEMPOTENCY_KEY_BLANK);
        }
        if (key.codePointCount(0, key.length()) > 128) throw CompanyLifecycleApiFailures.error(IDEMPOTENCY_KEY_TOO_LONG);
        return key;
    }
}
