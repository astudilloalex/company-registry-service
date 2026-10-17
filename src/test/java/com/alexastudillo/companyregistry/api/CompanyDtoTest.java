package com.alexastudillo.companyregistry.api;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.api.company.*;
import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompanyDtoTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void serializesNestedPublicFieldsWithTheirRealTypesAndOmitsNulls() throws Exception {
        var party = UUID.randomUUID(); var now = Instant.parse("2026-10-17T14:30:00.123456Z");
        var command = new CompanyRegistration.Command(new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString()),
                "key", party, "acme", "ágil", null, null);
        var row = CompanyRegistration.candidate(command, Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID);
        var identifier = new PartyView.Identifier(UUID.randomUUID(), party, UUID.randomUUID(), "EC_RUC", "*********0001", "VERIFIED", true,
                null, LocalDate.parse("2020-01-15"), null, now, "Verifier-aB", 2, now, now);
        var legal = new PartyView.LegalEntity("COMPAÑÍA S.A.S.", null, "SAS", "EC", LocalDate.parse("2020-01-15"), null);
        var view = new PartyView(party, "LEGAL_ENTITY", "COMPAÑÍA", "ACTIVE", 3, now, now, "Actor-aB", "Actor-cD", legal, List.of(identifier));
        var data = json.readTree(json.writeValueAsBytes(CompanyResponse.from(new CompanyRegistration.Result(row, view))));
        assertFalse(data.has("description")); assertFalse(data.has("logoUrl")); assertFalse(data.has("tenantId"));
        assertEquals("Actor-aB", data.path("createdBy").asText());
        assertEquals("2026-10-17T14:30:00.123456Z", data.path("createdAt").asText());
        assertTrue(data.path("version").isIntegralNumber());
        var detail = data.path("party");
        assertFalse(detail.has("naturalPersonDetails")); assertFalse(detail.has("tenantId"));
        assertEquals("2020-01-15", detail.path("legalEntityDetails").path("incorporatedOn").asText());
        assertFalse(detail.path("legalEntityDetails").has("dissolvedOn"));
        var item = detail.path("identifiers").get(0);
        assertTrue(item.path("isPrimary").isBoolean()); assertTrue(item.path("isPrimary").asBoolean());
        assertTrue(item.path("version").isIntegralNumber()); assertFalse(item.has("issuerCode"));
        assertEquals("*********0001", item.path("maskedValue").asText()); assertFalse(item.has("value"));
        var empty = new PartyView(party, "LEGAL_ENTITY", "COMPAÑÍA", "ACTIVE", 3, now, now, "a", "a", legal, List.of());
        assertEquals(0, json.valueToTree(CompanyResponse.from(new CompanyRegistration.Result(row, empty))).path("party").path("identifiers").size());
    }

    @Test void rejectsGeneratedUnknownAndNonStringInputBeforeCreatingACommand() {
        var prefix = "{\"partyId\":\"" + UUID.randomUUID() + "\",\"code\":\"acme\",\"operatingName\":\"ágil\"";
        for (var field : List.of("companyId", "id", "tenantId", "status", "version", "createdAt", "createdBy", "updatedAt", "updatedBy", "party", "identifiers", "unknown")) {
            assertThrows(MismatchedInputException.class, () -> json.readValue(prefix + ",\"" + field + "\":null}", CreateCompanyRequest.class));
        }
        for (var field : List.of("code", "operatingName", "description", "logoUrl")) {
            assertThrows(MismatchedInputException.class, () -> json.readValue("{\"" + field + "\":123}", CreateCompanyRequest.class));
        }
    }
}
