package com.alexastudillo.companyregistry.api;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.api.response.contract.ApiResponse;
import com.alexastudillo.companyregistry.api.verification.FoundationResource;
import com.alexastudillo.companyregistry.domain.model.Company;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

/** Rejects compilable but contract-invalid business resource response signatures. */
class ResourceSignatureTest {
    @Test void everyVerificationEndpointUsesExplicitEnvelopeWithAnApiDto() {
        verify(com.alexastudillo.companyregistry.api.company.CompanyResource.class);
        verify(com.alexastudillo.companyregistry.api.company.CompanyLifecycleResource.class);
        verify(com.alexastudillo.companyregistry.api.site.AddressResource.class);
        verify(FoundationResource.class);
        verify(com.alexastudillo.companyregistry.api.verification.PersistenceProbeResource.class);
        verify(com.alexastudillo.companyregistry.api.verification.ReferenceProbeResource.class);
    }
    @Test void guardRejectsBareDtoDomainAndPersistencePayloads() {
        for (var resource : new Class<?>[]{BareDto.class, DomainPayload.class, PersistencePayload.class}) {
            assertThrows(AssertionError.class, () -> verify(resource));
        }
    }
    private static void verify(Class<?> resource) {
        for (var method : resource.getDeclaredMethods()) {
            if (java.util.Arrays.stream(method.getAnnotations()).noneMatch(a -> a.annotationType().isAnnotationPresent(HttpMethod.class))) continue;
            var response = parameter(method.getGenericReturnType(), Uni.class);
            var envelope = parameter(response, RestResponse.class);
            var payload = parameter(envelope, ApiResponse.class);
            if(payload instanceof ParameterizedType) payload=parameter(payload, java.util.List.class);
            Class<?> dto = assertInstanceOf(Class.class, payload);
            assertTrue(dto.getPackageName().startsWith("com.alexastudillo.companyregistry.api."), method.toString());
            assertFalse(dto.isAnnotationPresent(jakarta.persistence.Entity.class));
        }
    }
    private static Type parameter(Type type, Class<?> raw) {
        var parameterized = assertInstanceOf(ParameterizedType.class, type);
        assertEquals(raw, parameterized.getRawType());
        assertEquals(1, parameterized.getActualTypeArguments().length);
        return parameterized.getActualTypeArguments()[0];
    }
    static class BareDto { @GET public Uni<com.alexastudillo.companyregistry.api.verification.ProbeResponse> read() { return null; } }
    static class DomainPayload { @GET public Uni<RestResponse<ApiResponse<Company>>> read() { return null; } }
    static class PersistencePayload { @GET public Uni<RestResponse<ApiResponse<com.alexastudillo.companyregistry.infrastructure.persistence.CompanyEntity>>> read() { return null; } }
}
