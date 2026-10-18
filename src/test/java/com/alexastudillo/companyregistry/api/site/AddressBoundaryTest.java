package com.alexastudillo.companyregistry.api.site;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import jakarta.ws.rs.core.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddressBoundaryTest {
    @Test
    void retainsRawTextPresenceNullAndExactDecimalNumbers() {
        var request = (UpdateAddressRequest) read(UpdateAddressRequest.class,
                "{\"countryCode\":\" ec \",\"addressLine2\":null,\"latitude\":-0.1234560,\"longitude\":78.123456000}");
        assertEquals(FieldChange.present(" ec "), request.patch().text().countryCode());
        assertEquals(FieldChange.absent(), request.patch().text().addressLine1());
        assertEquals(FieldChange.present(null), request.patch().text().addressLine2());
        assertEquals(new java.math.BigDecimal("-0.1234560"), request.patch().coordinates().latitude());
        assertEquals(new java.math.BigDecimal("78.123456000"), request.patch().coordinates().longitude());
        assertEquals(EmptyAddressCommand.INSTANCE, read(EmptyAddressCommand.class, ""));
        assertEquals(EmptyAddressCommand.INSTANCE, read(EmptyAddressCommand.class, "{}"));
    }

    @Test
    void fullStructuralPrecedenceComesBeforeFixedFieldValidationOrder() {
        for (var sample : new String[][] {{"", "request-body-required"}, {"{", "request-body-invalid-json"},
                {"{} true", "request-body-invalid-json"}, {"[] {}", "request-body-invalid-json"},
                {"[]", "request-body-must-be-object"}, {"null", "request-body-must-be-object"},
                {"{\"countryCode\":1,\"extra\":0}", "request-body-contains-unknown-fields"},
                {"{\"countryCode\":1,\"countryCode\":2}", "request-body-contains-duplicate-fields"},
                {"{\"addressLine1\":false,\"countryCode\":null}", "address-country-code-required"},
                {"{\"addressType\":null,\"countryCode\":false}", "address-type-required"},
                {"{\"addressType\":1}", "address-type-must-be-string"},
                {"{\"countryCode\":1}", "address-country-code-must-be-string"},
                {"{\"addressLine1\":false}", "address-line1-must-be-string"},
                {"{\"administrativeDivisionCode\":[]}", "address-administrative-division-code-must-be-string"},
                {"{\"addressLine2\":1}", "address-line2-must-be-string"},
                {"{\"houseNumber\":{}}", "address-house-number-must-be-string"},
                {"{\"postalCode\":true}", "address-postal-code-must-be-string"},
                {"{\"isPrimary\":null}", "address-is-primary-must-be-boolean"},
                {"{\"isPrimary\":\"true\"}", "address-is-primary-must-be-boolean"}}) {
            rejects(UpdateAddressRequest.class, sample[0], sample[1]);
        }
        rejects(CreateAddressRequest.class, "{}", "address-type-required");
        rejects(CreateAddressRequest.class, "{\"addressType\":\"PHYSICAL\"}", "address-country-code-required");
        rejects(CreateAddressRequest.class, "{\"addressType\":\"MAILING\",\"countryCode\":\"EC\"}", "address-line1-required");
        rejects(EmptyAddressCommand.class, "{\"addressType\":null}", "request-body-contains-unknown-fields");
    }

    @Test
    void coordinatePrecedenceRejectsIncompleteTypesMixedNullRangeAndPrecision() {
        for (var sample : new String[][] {{"{\"latitude\":\"x\"}", "address-coordinates-must-be-provided-together"},
                {"{\"latitude\":\"1\",\"longitude\":2}", "address-latitude-must-be-number"},
                {"{\"latitude\":null,\"longitude\":true}", "address-longitude-must-be-number"},
                {"{\"latitude\":null,\"longitude\":2}", "address-coordinates-must-be-both-null-or-numeric"},
                {"{\"latitude\":90.0000001,\"longitude\":0}", "address-latitude-out-of-range"},
                {"{\"latitude\":0.1234567,\"longitude\":180.0000001}", "address-longitude-out-of-range"},
                {"{\"latitude\":0.1234567,\"longitude\":0}", "address-latitude-precision-exceeded"}}) {
            rejects(UpdateAddressRequest.class, sample[0], sample[1]);
        }
    }

    @Test
    void closedQueryFiltersRejectRepeatedUnknownAndOverflowingParameters() {
        for (var sample : List.of("addressType", "isPrimary", "cursor", "limit")) {
            var params = new MultivaluedHashMap<String, String>(); params.addAll(sample, "a", "b");
            var failure = assertThrows(ApiResponseException.class, () -> AddressResource.selection(params));
            assertEquals("query-parameter-repeated", failure.getResponseCode().getCode());
        }
    }

    @Test
    void responseNumbersUseTheSameCanonicalFormForStoredRowsAndHistoricalResults() {
        var site = java.util.UUID.randomUUID();
        var scope = new com.alexastudillo.companyregistry.application.site.AddressScope(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), site);
        var now = java.time.Instant.parse("2026-10-18T20:00:00.123456Z");
        var row = new com.alexastudillo.companyregistry.application.persistence.RegistryData.AddressRecord(java.util.UUID.randomUUID(), site,
                com.alexastudillo.companyregistry.domain.model.AddressType.PHYSICAL, "EC", null, "LINE", null, null, null,
                new java.math.BigDecimal("90.000000"), new java.math.BigDecimal("0.0000010"), false,
                new com.alexastudillo.companyregistry.application.persistence.RegistryData.Audit(now, "Actor", now, "Actor"), null, null);
        var response = AddressResponse.from(new com.alexastudillo.companyregistry.application.site.AddressMutation.View(scope, row, 7));
        assertEquals(new java.math.BigDecimal("9E+1"), response.latitude());
        assertEquals(new java.math.BigDecimal("0.000001"), response.longitude());
        assertEquals(new java.math.BigDecimal("90.000000"), row.latitude());
    }

    private static void rejects(Class<?> type, String body, String code) {
        var failure = assertThrows(ApiResponseException.class, () -> read(type, body));
        assertEquals(400, failure.getResponseCode().getStatus());
        assertEquals(code, failure.getResponseCode().getCode());
    }

    @SuppressWarnings("unchecked")
    private static Object read(Class<?> type, String body) {
        return new AddressRequestReader().readFrom((Class<Object>) type, type, new java.lang.annotation.Annotation[0],
                MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
