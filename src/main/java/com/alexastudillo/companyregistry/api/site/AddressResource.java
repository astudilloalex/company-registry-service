package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.api.response.contract.PaginationMetadata;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.context.RequestOperationContext;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/v1/companies/{companyId}/sites/{siteId}/addresses")
@Produces(MediaType.APPLICATION_JSON)
public class AddressResource {
    private static final String ADDRESS_TYPE = "addressType";
    private static final String IS_PRIMARY = "isPrimary";
    private static final String LIMIT = "limit";
    private static final String CURSOR = "cursor";
    private static final Set<String> ALLOWED_PARAMETERS = Set.of(ADDRESS_TYPE, IS_PRIMARY, LIMIT, CURSOR);
    private final ResponseManager responses;
    private final RequestOperationContext context;
    private final MutateAddress mutations;
    private final ReadAddresses reads;

    @Inject public AddressResource(ResponseManager responses, RequestOperationContext context, MutateAddress mutations, ReadAddresses reads) {
        this.responses = responses; this.context = context; this.mutations = mutations; this.reads = reads;
    }

    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<AddressResponse>>> create(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context HttpHeaders headers, CreateAddressRequest request) {
        return mutate(company, site, null, headers, AddressMutation.Operation.CREATE, request == null ? null : request.patch())
                .map(result -> AddressResponse.from((AddressMutation.View) result))
                .map(data -> responses.customHttp(SiteResponseCode.CREATED, data));
    }

    @PATCH @Path("/{addressId}") @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<AddressResponse>>> update(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @PathParam("addressId") String address, @Context HttpHeaders headers, UpdateAddressRequest request) {
        return mutate(company, site, address, headers, AddressMutation.Operation.UPDATE, request == null ? null : request.patch())
                .map(result -> AddressResponse.from((AddressMutation.View) result)).map(responses::successHttp);
    }

    @DELETE @Path("/{addressId}") @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<AddressDeletionResponse>>> delete(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @PathParam("addressId") String address, @Context HttpHeaders headers, EmptyAddressCommand request) {
        return mutate(company, site, address, headers, AddressMutation.Operation.DELETE, AddressInformationPatch.empty())
                .map(result -> AddressDeletionResponse.from((AddressMutation.Deletion) result)).map(responses::successHttp);
    }

    @GET @Path("/{addressId}")
    public Uni<RestResponse<ApiResponse<AddressResponse>>> find(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @PathParam("addressId") String address) {
        return Uni.createFrom().deferred(() -> reads.find(scope(company, site), addressId(address)))
                .onFailure().transform(AddressApiFailures::translate).map(AddressResponse::from).map(responses::successHttp);
    }

    @GET
    public Uni<RestResponse<ApiResponse<List<AddressResponse>>>> list(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context UriInfo uri) {
        return Uni.createFrom().deferred(() -> reads.page(scope(company, site), selection(uri.getQueryParameters())))
                .onFailure().transform(AddressApiFailures::translate)
                .map(page -> responses.paginatedHttp(page.data().stream().map(AddressResponse::from).toList(),
                        new PaginationMetadata(page.nextCursor(), null, null, null, page.data().size())));
    }

    private Uni<AddressMutation.Result> mutate(String company, String site, String address, HttpHeaders headers,
            AddressMutation.Operation operation, AddressInformationPatch patch) {
        return Uni.createFrom().deferred(() -> {
            var scope = scope(company, site);
            var id = operation == AddressMutation.Operation.CREATE ? null : addressId(address);
            long version = SiteMutationResource.expectedVersion(headers);
            var keys = headers.getRequestHeader("Idempotency-Key");
            String key = operation == AddressMutation.Operation.CREATE || keys != null && !keys.isEmpty() ? SiteResource.key(keys) : null;
            if (patch == null) throw AddressApiFailures.error("request-body-required");
            // The reader validates input while retaining the raw intention for the selected acceptance policy.
            return mutations.execute(new AddressMutation.Command(context.require(), scope, version, key,
                    new AddressMutation.Intent(operation, id, patch)));
        }).onFailure().transform(AddressApiFailures::translate);
    }

    private AddressScope scope(String company, String site) {
        var business = context.require();
        return new AddressScope(business.tenantId(), SiteResource.uuid(company, SiteResponseCode.COMPANY_ID_INVALID),
                SiteResource.uuid(site, SiteResponseCode.SITE_ID_INVALID));
    }

    private static UUID addressId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw AddressApiFailures.error("address-id-invalid");
        }
        return UUID.fromString(value);
    }

    static AddressSelection.Request selection(MultivaluedMap<String, String> parameters) {
        validateParameters(parameters);
        var type = parseAddressType(parameters);
        Boolean primary = null;
        if (parameters.containsKey(IS_PRIMARY)) {
            var value = parameters.getFirst(IS_PRIMARY);
            if (!"true".equals(value) && !"false".equals(value)) {
                throw AddressApiFailures.error("address-is-primary-filter-invalid");
            }
            primary = Boolean.valueOf(value);
        }
        var cursor = parseCursor(parameters.getFirst(CURSOR));
        return new AddressSelection.Request(new AddressSelection.Filters(type, primary), limit(parameters.getFirst(LIMIT)), cursor);
    }

    private static void validateParameters(MultivaluedMap<String, String> parameters) {
        for (var name : parameters.keySet()) {
            if (!ALLOWED_PARAMETERS.contains(name)) throw AddressApiFailures.error("query-parameter-not-supported");
        }
        for (var values : parameters.values()) {
            if (values.size() != 1) throw AddressApiFailures.error("query-parameter-repeated");
        }
    }

    private static AddressType parseAddressType(MultivaluedMap<String, String> parameters) {
        if (!parameters.containsKey(ADDRESS_TYPE)) return null;
        try {
            return AddressType.valueOf(parameters.getFirst(ADDRESS_TYPE));
        } catch (IllegalArgumentException _) {
            throw AddressApiFailures.error("address-type-not-supported");
        }
    }

    private static String parseCursor(String cursor) {
        if (cursor != null && (cursor.isBlank() || cursor.length() > 4096)) {
            throw AddressApiFailures.error("address-cursor-invalid-or-incompatible");
        }
        return cursor;
    }

    private static int limit(String value) {
        if (value == null) return AddressSelection.DEFAULT_LIMIT;
        if (!value.matches("\\d+")) throw AddressApiFailures.error("address-page-limit-must-be-an-integer");
        var number = new BigInteger(value);
        if (number.signum() < 1 || number.compareTo(BigInteger.valueOf(200)) > 0) {
            throw AddressApiFailures.error("address-page-limit-must-be-between-1-and-200");
        }
        return number.intValueExact();
    }
}
