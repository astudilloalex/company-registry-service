package com.alexastudillo.companyregistry.api.site;

import static com.alexastudillo.companyregistry.api.site.SiteResponseCode.*;
import com.alexastudillo.api.response.contract.*;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.context.RequestOperationContext;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/v1/companies/{companyId}/sites")
@Produces(MediaType.APPLICATION_JSON)
public class SiteResource {
    private final ResponseManager responses;
    private final RequestOperationContext context;
    private final RegisterSite registration;
    private final ReadSites reads;

    @Inject
    public SiteResource(ResponseManager responses, RequestOperationContext context, RegisterSite registration,
            ReadSites reads) {
        this.responses = responses;
        this.context = context;
        this.registration = registration;
        this.reads = reads;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<SiteResponse>>> create(@PathParam("companyId") String company,
            @Context HttpHeaders headers, CreateSiteRequest request) {
        return Uni.createFrom().deferred(() -> {
            var business = context.require();
            var companyId = uuid(company, COMPANY_ID_INVALID);
            var key = key(headers.getRequestHeader("Idempotency-Key"));
            if (request == null)
                throw SiteApiFailures.error(REQUEST_BODY_REQUIRED);
            return registration.execute(request.command(business, key, companyId));
        }).onFailure().transform(SiteApiFailures::translate).map(SiteResponse::from)
                .map(data -> responses.customHttp(CREATED, data));
    }

    @GET
    @Path("/{siteId}")
    public Uni<RestResponse<ApiResponse<SiteResponse>>> find(@PathParam("companyId") String company,
            @PathParam("siteId") String site) {
        return Uni.createFrom()
                .deferred(() -> reads.find(context.require(), uuid(company, COMPANY_ID_INVALID),
                        uuid(site, SITE_ID_INVALID)))
                .onFailure().transform(SiteApiFailures::translate).map(SiteResponse::from).map(responses::successHttp);
    }

    @GET
    public Uni<RestResponse<ApiResponse<List<SiteResponse>>>> list(@PathParam("companyId") String company,
            @Context UriInfo uri) {
        return Uni.createFrom()
                .deferred(() -> reads.page(context.require(), uuid(company, COMPANY_ID_INVALID),
                        selection(uri.getQueryParameters())))
                .onFailure().transform(SiteApiFailures::translate)
                .map(page -> responses.paginatedHttp(page.data().stream().map(SiteResponse::from).toList(),
                        new PaginationMetadata(page.nextCursor(), null, null, null, page.data().size())));
    }

    static SiteSelection.Request selection(MultivaluedMap<String, String> params) {
        for (var name : params.keySet())
            if (!Set.of("code", "siteType", "status", "cursor", "limit").contains(name))
                throw SiteApiFailures.error(QUERY_PARAMETER_NOT_SUPPORTED);
        for (var values : params.values())
            if (values.size() != 1)
                throw SiteApiFailures.error(QUERY_PARAMETER_REPEATED);
        var siteType = parseEnum(params.getFirst("siteType"), SiteType.class, SITE_TYPE_NOT_SUPPORTED);
        var siteStatus = parseEnum(params.getFirst("status"), SiteStatus.class, SITE_STATUS_NOT_SUPPORTED);
        var limit = parseLimit(params.getFirst("limit"));
        return new SiteSelection.Request(new SiteSelection.Filters(params.getFirst("code"), siteType, siteStatus),
                limit, params.getFirst("cursor"));
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, SiteResponseCode error) {
        if (value == null)
            return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException _) {
            throw SiteApiFailures.error(error);
        }
    }

    private static int parseLimit(String supplied) {
        if (supplied == null)
            return 50;
        if (!supplied.matches("\\d+"))
            throw SiteApiFailures.error(SITE_PAGE_LIMIT_MUST_BE_AN_INTEGER);
        var number = new java.math.BigInteger(supplied);
        if (number.signum() <= 0 || number.compareTo(java.math.BigInteger.valueOf(200)) > 0)
            throw SiteApiFailures.error(SITE_PAGE_LIMIT_MUST_BE_BETWEEN_1_AND_200);
        return number.intValueExact();
    }

    static String key(List<String> keys) {
        if (keys == null || keys.isEmpty())
            throw SiteApiFailures.error(IDEMPOTENCY_KEY_REQUIRED);
        if (keys.size() != 1)
            throw SiteApiFailures.error(IDEMPOTENCY_KEY_DUPLICATED);
        var value = keys.getFirst();
        if (value == null || value.isBlank())
            throw SiteApiFailures.error(IDEMPOTENCY_KEY_BLANK);
        if (value.codePointCount(0, value.length()) > 128)
            throw SiteApiFailures.error(IDEMPOTENCY_KEY_TOO_LONG);
        return value;
    }

    static UUID uuid(String value, SiteResponseCode code) {
        if (value == null
                || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw SiteApiFailures.error(code);
        return UUID.fromString(value);
    }
}
