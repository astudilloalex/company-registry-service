package com.alexastudillo.companyregistry.api.context;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.api.error.CompanyResponseCode;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import org.jboss.logging.MDC;

@Provider @PreMatching @Priority(Priorities.AUTHENTICATION)
public class BusinessContextFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final String PROCESS = BusinessContextFilter.class.getName() + ".process";
    @Inject RequestOperationContext operation;
    @Inject RoutingContext routing;

    @Override public void filter(ContainerRequestContext request) {
        var path = request.getUriInfo().getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (!(path.equals("v1") || path.startsWith("v1/"))) return;
        var process = single(request, "Process-Id", CompanyResponseCode.INVALID_PROCESS_ID);
        uuid(process, CompanyResponseCode.INVALID_PROCESS_ID);
        request.setProperty(PROCESS, process);
        routing.response().putHeader("Process-Id", process);
        MDC.put("processId", process);
        // End handler covers failure/cancellation paths as well as normal REST responses.
        routing.addEndHandler(ignored -> MDC.remove("processId"));
        var tenant = uuid(single(request, "Tenant-Id", CompanyResponseCode.INVALID_TENANT_ID), CompanyResponseCode.INVALID_TENANT_ID);
        var actor = single(request, "User-Id", CompanyResponseCode.INVALID_USER_ID);
        if (actor.isBlank() || actor.codePointCount(0, actor.length()) > 128 || actor.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiResponseException(CompanyResponseCode.INVALID_USER_ID);
        }
        operation.set(new OperationContext(tenant, actor, process));
    }

    @Override public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        var process = request.getProperty(PROCESS);
        if (process != null) {
            response.getHeaders().putSingle("Process-Id", process);
            MDC.remove("processId");
        }
    }

    private static String single(ContainerRequestContext request, String header, CompanyResponseCode failure) {
        var values = request.getHeaders().get(header);
        if (values == null || values.size() != 1 || values.getFirst() == null) throw new ApiResponseException(failure);
        return values.getFirst();
    }
    private static UUID uuid(String value, CompanyResponseCode failure) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ApiResponseException(failure);
        }
        return UUID.fromString(value);
    }
}
