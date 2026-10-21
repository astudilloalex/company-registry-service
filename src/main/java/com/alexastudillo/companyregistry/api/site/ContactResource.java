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

@Path("/v1/companies/{companyId}/sites/{siteId}/contacts")
@Produces(MediaType.APPLICATION_JSON)
public class ContactResource {
    private static final String CONTACT_TYPE = "contactType";
    private static final String IS_PRIMARY = "isPrimary";
    private static final String LIMIT = "limit";
    private static final String CURSOR = "cursor";
    private static final Set<String> ALLOWED_PARAMETERS = Set.of(CONTACT_TYPE, IS_PRIMARY, LIMIT, CURSOR);
    @Context jakarta.ws.rs.container.ContainerRequestContext boundary;
    private final ResponseManager responses;
    private final RequestOperationContext context;
    private final MutateContact mutations;
    private final ReadContacts reads;

    @Inject public ContactResource(ResponseManager responses, RequestOperationContext context, MutateContact mutations, ReadContacts reads) {
        this.responses = responses; this.context = context; this.mutations = mutations; this.reads = reads;
    }

    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<ContactResponse>>> create(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context HttpHeaders headers, CreateContactRequest request) {
        return mutate(ContactMutation.Operation.CREATE, request == null ? null : request.patch())
                .map(result -> ContactResponse.from((ContactMutation.View) result))
                .map(data -> responses.customHttp(SiteResponseCode.CREATED, data));
    }

    @PATCH @Path("/{contactId}") @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<ContactResponse>>> update(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @PathParam("contactId") String contact, @Context HttpHeaders headers, UpdateContactRequest request) {
        return mutate(ContactMutation.Operation.UPDATE, request == null ? null : request.patch())
                .map(result -> ContactResponse.from((ContactMutation.View) result)).map(responses::successHttp);
    }

    @DELETE @Path("/{contactId}") @Consumes(MediaType.APPLICATION_JSON)
    public Uni<RestResponse<ApiResponse<ContactDeletionResponse>>> delete(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @PathParam("contactId") String contact, @Context HttpHeaders headers, EmptyContactCommand request) {
        return mutate(ContactMutation.Operation.DELETE, ContactInformationPatch.empty())
                .map(result -> ContactDeletionResponse.from((ContactMutation.Deletion) result)).map(responses::successHttp);
    }

    @GET @Path("/{contactId}")
    public Uni<RestResponse<ApiResponse<ContactResponse>>> find(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @PathParam("contactId") String contact) {
        return Uni.createFrom().deferred(() -> reads.find(scope(), contactId(contact)))
                .onFailure().transform(ContactApiFailures::translate).map(ContactResponse::from).map(responses::successHttp);
    }

    @GET
    public Uni<RestResponse<ApiResponse<List<ContactResponse>>>> list(@PathParam("companyId") String company,
            @PathParam("siteId") String site, @Context UriInfo uri) {
        return Uni.createFrom().deferred(() -> reads.page(scope(), selection(uri.getQueryParameters())))
                .onFailure().transform(ContactApiFailures::translate)
                .map(page -> responses.paginatedHttp(page.data().stream().map(ContactResponse::from).toList(),
                        new PaginationMetadata(page.nextCursor(), null, null, null, page.data().size())));
    }

    private Uni<ContactMutation.Result> mutate(ContactMutation.Operation operation, ContactInformationPatch patch) {
        return Uni.createFrom().deferred(() -> {
            var scope = scope();
            var id = operation == ContactMutation.Operation.CREATE ? null : (UUID) boundary.getProperty("contact.contactId");
            long version = (Long) boundary.getProperty("contact.expectedVersion");
            String key = (String) boundary.getProperty("contact.key");
            if (patch == null) throw ContactApiFailures.error("request-body-required");
            // The reader validates input while retaining the raw intention for the selected acceptance policy.
            return mutations.execute(new ContactMutation.Command(context.require(), scope, version, key,
                    new ContactMutation.Intent(operation, id, patch)));
        }).onFailure().transform(ContactApiFailures::translate);
    }

    private ContactScope scope() {
        var business = context.require();
        return new ContactScope(business.tenantId(), (UUID) boundary.getProperty("contact.companyId"),
                (UUID) boundary.getProperty("contact.siteId"));
    }

    static UUID contactId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw ContactApiFailures.error("contact-id-invalid");
        }
        return UUID.fromString(value);
    }

    static ContactSelection.Request selection(MultivaluedMap<String, String> parameters) {
        validateParameters(parameters);
        var type = parseContactType(parameters);
        Boolean primary = null;
        if (parameters.containsKey(IS_PRIMARY)) {
            var value = parameters.getFirst(IS_PRIMARY);
            if (!"true".equals(value) && !"false".equals(value)) {
                throw ContactApiFailures.error("contact-is-primary-filter-invalid");
            }
            primary = Boolean.valueOf(value);
        }
        var cursor = parseCursor(parameters.getFirst(CURSOR));
        return new ContactSelection.Request(new ContactSelection.Filters(type, primary), limit(parameters.getFirst(LIMIT)), cursor);
    }

    private static void validateParameters(MultivaluedMap<String, String> parameters) {
        for (var name : parameters.keySet()) {
            if (!ALLOWED_PARAMETERS.contains(name)) throw ContactApiFailures.error("query-parameter-not-supported");
        }
        for (var values : parameters.values()) {
            if (values.size() != 1) throw ContactApiFailures.error("query-parameter-repeated");
        }
    }

    private static ContactType parseContactType(MultivaluedMap<String, String> parameters) {
        if (!parameters.containsKey(CONTACT_TYPE)) return null;
        try {
            return ContactType.valueOf(parameters.getFirst(CONTACT_TYPE));
        } catch (IllegalArgumentException _) {
            throw ContactApiFailures.error("contact-type-not-supported");
        }
    }

    private static String parseCursor(String cursor) {
        if (cursor != null && (cursor.isBlank() || cursor.length() > 4096)) {
            throw ContactApiFailures.error("contact-cursor-invalid-or-incompatible");
        }
        return cursor;
    }

    private static int limit(String value) {
        if (value == null) return ContactSelection.DEFAULT_LIMIT;
        if (!value.matches("\\d+")) throw ContactApiFailures.error("contact-page-limit-must-be-an-integer");
        var number = new BigInteger(value);
        if (number.signum() < 1 || number.compareTo(BigInteger.valueOf(200)) > 0) {
            throw ContactApiFailures.error("contact-page-limit-must-be-between-1-and-200");
        }
        return number.intValueExact();
    }
}
