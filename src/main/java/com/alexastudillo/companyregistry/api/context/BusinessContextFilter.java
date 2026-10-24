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
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/** Validates business context and scopes diagnostic identity to one HTTP request. */
@Provider @PreMatching @Priority(Priorities.AUTHENTICATION)
public class BusinessContextFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final Logger LOG = Logger.getLogger(BusinessContextFilter.class);
    private static final String PROCESS = BusinessContextFilter.class.getName() + ".process";
    private static final String PROCESS_ID_HEADER = "Process-Id";
    private static final String TENANT_ID_HEADER = "Tenant-Id";
    private static final String USER_ID_HEADER = "User-Id";
    private static final String PROCESS_ID_MDC_KEY = "processId";
    private static final String TENANT_ID_MDC_KEY = "tenantId";
    private static final String USER_ID_MDC_KEY = "userId";
    private final RequestOperationContext operation;
    private final RoutingContext routing;

    @Inject
    public BusinessContextFilter(RequestOperationContext operation, RoutingContext routing) {
        this.operation = operation;
        this.routing = routing;
    }

    @Override public void filter(ContainerRequestContext request) {
        var path = request.getUriInfo().getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (!(path.equals("v1") || path.startsWith("v1/"))) return;
        var process = single(request, PROCESS_ID_HEADER, CompanyResponseCode.INVALID_PROCESS_ID);
        uuid(process, CompanyResponseCode.INVALID_PROCESS_ID);
        request.setProperty(PROCESS, process);
        routing.response().putHeader(PROCESS_ID_HEADER, process);
        var diagnostics = new LinkedHashMap<String, String>();
        diagnostics.put(PROCESS_ID_MDC_KEY, process);
        MDC.put(PROCESS_ID_MDC_KEY, process);
        long started = System.nanoTime();
        String method = request.getMethod();
        // End handler covers failure/cancellation paths as well as normal REST responses.
        routing.addEndHandler(ignored -> complete(diagnostics, method, started));
        var tenant = uuid(single(request, TENANT_ID_HEADER, CompanyResponseCode.INVALID_TENANT_ID), CompanyResponseCode.INVALID_TENANT_ID);
        diagnostics.put(TENANT_ID_MDC_KEY, tenant.toString());
        MDC.put(TENANT_ID_MDC_KEY, tenant.toString());
        var actor = single(request, USER_ID_HEADER, CompanyResponseCode.INVALID_USER_ID);
        if (actor.isBlank() || actor.codePointCount(0, actor.length()) > 128 || actor.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiResponseException(CompanyResponseCode.INVALID_USER_ID);
        }
        diagnostics.put(USER_ID_MDC_KEY, actor);
        MDC.put(USER_ID_MDC_KEY, actor);
        operation.set(new OperationContext(tenant, actor, process));
    }

    @Override public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        var process = request.getProperty(PROCESS);
        if (process != null) {
            response.getHeaders().putSingle(PROCESS_ID_HEADER, process);
            clearDiagnostics();
        }
    }

    private void complete(Map<String, String> diagnostics, String method, long started) {
        try {
            diagnostics.forEach(MDC::put);
            LOG.infof("HTTP completed method=%s status=%d durationMs=%.3f", method,
                    routing.response().getStatusCode(), (System.nanoTime() - started) / 1_000_000.0);
        } finally {
            clearDiagnostics();
        }
    }

    private static void clearDiagnostics() {
        MDC.remove(PROCESS_ID_MDC_KEY);
        MDC.remove(TENANT_ID_MDC_KEY);
        MDC.remove(USER_ID_MDC_KEY);
    }

    private static String single(ContainerRequestContext request, String header, CompanyResponseCode failure) {
        var values = request.getHeaders().get(header);
        if (values == null || values.size() != 1 || values.getFirst() == null) throw new ApiResponseException(failure);
        return values.getFirst();
    }
    private static UUID uuid(String value, CompanyResponseCode failure) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ApiResponseException(failure);
        }
        return UUID.fromString(value);
    }
}
