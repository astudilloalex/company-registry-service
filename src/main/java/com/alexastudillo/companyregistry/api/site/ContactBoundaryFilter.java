package com.alexastudillo.companyregistry.api.site;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/** Route and mutation headers precede the complete strict JSON reader. */
@Provider
@Priority(Priorities.AUTHENTICATION + 100)
public class ContactBoundaryFilter implements ContainerRequestFilter {
    @Context ResourceInfo resource;
    @Context HttpHeaders headers;

    @Override
    public void filter(ContainerRequestContext request) {
        if (resource.getResourceClass() != ContactResource.class) return;
        var parameters = request.getUriInfo().getPathParameters();
        request.setProperty("contact.companyId", SiteResource.uuid(parameters.getFirst("companyId"), SiteResponseCode.COMPANY_ID_INVALID));
        request.setProperty("contact.siteId", SiteResource.uuid(parameters.getFirst("siteId"), SiteResponseCode.SITE_ID_INVALID));
        if (parameters.containsKey("contactId")) request.setProperty("contact.contactId", ContactResource.contactId(parameters.getFirst("contactId")));
        String method = request.getMethod();
        if ("POST".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)) {
            request.setProperty("contact.expectedVersion", SiteMutationResource.expectedVersion(headers));
            var keys = headers.getRequestHeader("Idempotency-Key");
            if ("POST".equals(method) || keys != null && !keys.isEmpty()) request.setProperty("contact.key", SiteResource.key(keys));
        }
    }
}
