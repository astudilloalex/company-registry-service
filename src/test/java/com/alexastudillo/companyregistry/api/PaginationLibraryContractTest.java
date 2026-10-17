package com.alexastudillo.companyregistry.api;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.api.response.contract.PaginationMetadata;
import com.alexastudillo.api.response.infrastructure.quarkus.ResponseManager;
import com.alexastudillo.companyregistry.api.header.ExpectedVersionParser;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks documented conventions against the consumed library, not a local envelope replica. */
@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class PaginationLibraryContractTest {
    @Inject ObjectMapper json;
    @Inject ResponseManager responses;
    @Test void librarySerializesTopLevelMetadataAndOmitsNulls() {
        var response = responses.paginatedHttp(List.of("entry"), new PaginationMetadata("Key-aB", "Prev-aB", 12, 3, 1));
        JsonNode body = json.valueToTree(response.getEntity());
        assertEquals(response.getStatus(), body.path("status").asInt());
        assertEquals("successful", body.path("code").asText());
        assertTrue(body.path("data").isArray()); assertFalse(body.has("pagination"));
        assertEquals("Key-aB", body.path("nextCursor").asText()); assertEquals("Prev-aB", body.path("prevCursor").asText());
        assertEquals(12, body.path("totalElements").asInt()); assertEquals(3, body.path("totalPages").asInt());
        assertEquals(1, body.path("numberOfElements").asInt());
        JsonNode empty = json.valueToTree(responses.paginatedHttp(List.of(), new PaginationMetadata(null, null, null, null, 0)).getEntity());
        assertTrue(empty.path("data").isEmpty()); assertFalse(empty.has("nextCursor")); assertFalse(empty.has("prevCursor"));
        assertFalse(empty.has("totalElements")); assertFalse(empty.has("totalPages"));
        assertThrows(IllegalArgumentException.class, () -> new PaginationMetadata(null, null, -1, 0, 0));
    }
    @Test void expectedVersionUsesCanonicalDecimalInt64Convention() {
        assertEquals(0, ExpectedVersionParser.parse("0").value());
        assertEquals(Long.MAX_VALUE, ExpectedVersionParser.parse("9223372036854775807").value());
        for (var invalid : new String[]{null, "", "-1", "+1", "01", "1.0", " 1", "1 ", "\"1\"", "*", "9223372036854775808"}) {
            assertThrows(IllegalArgumentException.class, () -> ExpectedVersionParser.parse(invalid));
        }
    }
}
