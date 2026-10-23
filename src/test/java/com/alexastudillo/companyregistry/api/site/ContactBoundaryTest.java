package com.alexastudillo.companyregistry.api.site;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.api.response.application.ApiResponseException;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import jakarta.ws.rs.core.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContactBoundaryTest {
    @Test
    void retainsOriginalStringsIndependentPresenceAndNullUntilPolicySelection() {
        var request = (UpdateContactRequest) read(UpdateContactRequest.class,
                "{\"value\":\" Sales@Example.COM \",\"label\":null}");
        assertEquals(FieldChange.present(" Sales@Example.COM "), request.patch().value());
        assertEquals(FieldChange.absent(), request.patch().contactType());
        assertEquals(FieldChange.present(null), request.patch().label());
        assertFalse(request.patch().isPrimary().present());
        assertEquals(EmptyContactCommand.INSTANCE, read(EmptyContactCommand.class, ""));
        assertEquals(EmptyContactCommand.INSTANCE, read(EmptyContactCommand.class, "{}"));
    }

    @Test
    void completeStructurePrecedesFieldTypesAndRequiredChecks() {
        for (var sample : new String[][] {{"", "request-body-required"}, {"{", "request-body-invalid-json"},
                {"{} true", "request-body-invalid-json"}, {"[] {}", "request-body-invalid-json"},
                {"[]", "request-body-must-be-object"}, {"null", "request-body-must-be-object"},
                {"{\"value\":1,\"extra\":0}", "request-body-contains-unknown-fields"},
                {"{\"value\":1,\"value\":2}", "request-body-contains-duplicate-fields"},
                {"{\"value\":false,\"contactType\":null}", "contact-type-required"},
                {"{\"contactType\":1}", "contact-type-must-be-string"},
                {"{\"value\":1}", "contact-value-must-be-string"},
                {"{\"label\":[]}", "contact-label-must-be-string"},
                {"{\"value\":null}", "contact-value-required"},
                {"{\"value\":\" \"}", "contact-value-required"},
                {"{\"isPrimary\":null}", "contact-is-primary-must-be-boolean"},
                {"{\"isPrimary\":\"true\"}", "contact-is-primary-must-be-boolean"}}) {
            rejects(UpdateContactRequest.class, sample[0], sample[1]);
        }
        rejects(CreateContactRequest.class, "{}", "contact-type-required");
        rejects(CreateContactRequest.class, "{\"contactType\":\"EMAIL\"}", "contact-value-required");
        rejects(EmptyContactCommand.class, "{\"label\":null}", "request-body-contains-unknown-fields");
    }

    @Test
    void readerLeavesSemanticValidationToTheSelectedHistoricalPolicy() {
        var request = (UpdateContactRequest) read(UpdateContactRequest.class,
                "{\"contactType\":\"unknown-historical\",\"value\":\"historical-format\",\"label\":\" \"}");
        assertEquals("unknown-historical", request.patch().contactType().value());
        assertEquals("historical-format", request.patch().value().value());
        assertEquals(" ", request.patch().label().value());
    }

    @Test
    void exactQueryFiltersRejectRepeatedUnknownAndOverflowingParameters() {
        for (var name : List.of("contactType", "isPrimary", "cursor", "limit")) {
            var params = new MultivaluedHashMap<String, String>(); params.addAll(name, "a", "b");
            var failure = assertThrows(ApiResponseException.class, () -> ContactResource.selection(params));
            assertEquals("query-parameter-repeated", failure.getResponseCode().getCode());
        }
        for (var sample : new String[][] {{"other", "x", "query-parameter-not-supported"},
                {"limit", "999999999999999999999999", "contact-page-limit-must-be-between-1-and-200"},
                {"limit", "a", "contact-page-limit-must-be-an-integer"},
                {"contactType", "email", "contact-type-not-supported"},
                {"isPrimary", "TRUE", "contact-is-primary-filter-invalid"}}) {
            var params = new MultivaluedHashMap<String, String>(); params.add(sample[0], sample[1]);
            var failure = assertThrows(ApiResponseException.class, () -> ContactResource.selection(params));
            assertEquals(sample[2], failure.getResponseCode().getCode());
        }
    }

    private static void rejects(Class<?> type, String body, String code) {
        var failure = assertThrows(ApiResponseException.class, () -> read(type, body));
        assertEquals(400, failure.getResponseCode().getStatus());
        assertEquals(code, failure.getResponseCode().getCode());
    }

    @SuppressWarnings("unchecked")
    private static Object read(Class<?> type, String body) {
        return new ContactRequestReader().readFrom((Class<Object>) type, type, new java.lang.annotation.Annotation[0],
                MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
