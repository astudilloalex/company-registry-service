package com.alexastudillo.companyregistry.contract;

import static org.junit.jupiter.api.Assertions.*;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared external HTTP assertion, usable by JVM and native-process suites. */
public final class OpenApiAssertions {
    private OpenApiAssertions() {}
    public static void publishedMatchesSource(String source, String published) {
        var parser = new OpenAPIV3Parser();
        var expectedResult = parser.readContents(source, null, null);
        var actualResult = parser.readContents(published, null, null);
        assertNotNull(expectedResult.getOpenAPI(), () -> expectedResult.getMessages().toString());
        assertNotNull(actualResult.getOpenAPI(), () -> actualResult.getMessages().toString());
        assertTrue(expectedResult.getMessages().isEmpty(), () -> expectedResult.getMessages().toString());
        assertTrue(actualResult.getMessages().isEmpty(), () -> actualResult.getMessages().toString());
        var expected = expectedResult.getOpenAPI(); var actual = actualResult.getOpenAPI();
        assertEquals(expected.getInfo(), actual.getInfo());
        assertEquals(Set.of("/v1/companies", "/v1/companies/{companyId}", "/v1/companies/{companyId}/sites",
                "/v1/companies/{companyId}/sites/{siteId}"), actual.getPaths().keySet());
        var site = actual.getComponents().getSchemas().get("SiteResponse");
        assertEquals(12, site.getRequired().size());
        assertEquals(Set.of("siteId", "companyId", "code", "name", "siteType", "status", "version", "effectivelyOperational",
                "createdAt", "createdBy", "updatedAt", "updatedBy"), site.getProperties().keySet());
        var additional = actual.getComponents().getSchemas().get("CreateSiteRequest").getAdditionalProperties();
        assertTrue(Boolean.FALSE.equals(additional) || additional instanceof io.swagger.v3.oas.models.media.Schema<?> schema
                && Boolean.FALSE.equals(schema.getBooleanSchemaValue()));
        assertNotNull(actual.getPaths().get("/v1/companies/{companyId}/sites").getPost().getResponses().get("201"));
        assertNotNull(actual.getPaths().get("/v1/companies/{companyId}/sites").getGet());
        assertNotNull(actual.getPaths().get("/v1/companies/{companyId}/sites/{siteId}").getGet());
        assertEquals(expected.getPaths(), actual.getPaths());
        assertEquals(expected.getComponents().getSchemas(), actual.getComponents().getSchemas());
        assertEquals(expected.getComponents().getParameters(), actual.getComponents().getParameters());
        assertEquals(expected.getComponents().getHeaders(), actual.getComponents().getHeaders());
        assertEquals(expected.getComponents().getExamples(), actual.getComponents().getExamples());
        assertEquals(expected.getComponents().getResponses(), actual.getComponents().getResponses());
        var update = actual.getPaths().get("/v1/companies/{companyId}").getPatch();
        assertNotNull(update); assertNotNull(update.getResponses().get("200"));
        assertNotNull(update.getResponses().get("412"));
        assertTrue(actual.getComponents().getParameters().get("IdempotencyKey").getRequired());
        assertFalse(actual.getComponents().getParameters().get("UpdateIdempotencyKey").getRequired());
        assertTrue(actual.getComponents().getParameters().get("ExpectedVersion").getRequired());
        assertEquals(Set.of("code","operatingName","description","logoUrl"), actual.getComponents().getSchemas().get("UpdateCompanyRequest").getProperties().keySet());
        assertFalse(actual.getComponents().getSchemas().get("CompanyOperationalResponse").getProperties().containsKey("party"));
        assertTrue(actual.getComponents().getSchemas().get("CompanyResponse").getRequired().contains("party"));
        var json = new ObjectMapper();
        for (var example : expected.getComponents().getExamples().values()) {
            JsonNode value = json.valueToTree(example.getValue());
            assertTrue(value.isObject()); assertTrue(value.path("status").isInt());
            assertTrue(value.path("code").isTextual());
            assertFalse(value.has("message")); assertFalse(value.has("stackTrace"));
            String schema = !value.has("data") ? "ErrorResponse" : value.get("data").isArray() ? "CompanyCollectionEnvelope" : "CompanyEnvelope";
            assertExample(value, expected.getComponents().getSchemas().get(schema), expected.getComponents().getSchemas());
        }
        java.util.Map<?, ?> pagination = expected.getComponents().getSchemas().get("CompanyCollectionEnvelope").getProperties();
        assertTrue(pagination.keySet().containsAll(Set.of("nextCursor", "prevCursor", "totalElements", "totalPages", "numberOfElements")));
        assertFalse(pagination.containsKey("pagination"));
    }

    private static void assertExample(JsonNode value, io.swagger.v3.oas.models.media.Schema<?> schema,
                                      java.util.Map<String, ?> schemas) {
        if (schema.get$ref() != null) {
            assertExample(value, (io.swagger.v3.oas.models.media.Schema<?>) schemas.get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1)), schemas); return;
        }
        if (schema.getProperties() != null) {
            assertTrue(value.isObject());
            if (schema.getRequired() != null) for (var required : schema.getRequired()) assertTrue(value.has(required), required);
            if (Boolean.FALSE.equals(schema.getAdditionalProperties())) {
                value.fieldNames().forEachRemaining(name -> assertTrue(schema.getProperties().containsKey(name), name));
            }
            schema.getProperties().forEach((name, property) -> { if (value.has(name)) assertExample(value.get(name), property, schemas); });
        }
        if (schema.getItems() != null) {
            assertTrue(value.isArray()); value.forEach(item -> assertExample(item, schema.getItems(), schemas));
        }
        if (schema.getEnum() != null) assertTrue(schema.getEnum().stream().anyMatch(item -> new ObjectMapper().valueToTree(item).equals(value)));
        if (schema.getConst() != null) assertEquals(new ObjectMapper().valueToTree(schema.getConst()), value);
        if (schema.getMinimum() != null) assertTrue(value.isNumber() && value.decimalValue().compareTo(schema.getMinimum()) >= 0);
        if (schema.getMaximum() != null) assertTrue(value.isNumber() && value.decimalValue().compareTo(schema.getMaximum()) <= 0);
        if (schema.getMaxLength() != null) assertTrue(value.isTextual() && value.textValue().codePointCount(0, value.textValue().length()) <= schema.getMaxLength());
        if (schema.getMinLength() != null) assertTrue(value.isTextual() && value.textValue().codePointCount(0, value.textValue().length()) >= schema.getMinLength());
        if ("uuid".equals(schema.getFormat())) assertEquals(value.asText().toLowerCase(java.util.Locale.ROOT), java.util.UUID.fromString(value.asText()).toString());
        if ("date-time".equals(schema.getFormat())) assertDoesNotThrow(() -> java.time.Instant.parse(value.asText()));
    }
}
