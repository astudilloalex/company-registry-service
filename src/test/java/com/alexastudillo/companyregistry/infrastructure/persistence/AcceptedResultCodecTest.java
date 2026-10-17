package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.api.company.CompanyResponse;
import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AcceptedResultCodecTest {
    @Test void roundTripsCompleteOriginalPublicContentWithoutDependencies() throws Exception {
        var now = Instant.parse("2026-10-17T14:30:00.123456Z"); var party = UUID.randomUUID();
        var command = new CompanyRegistration.Command(new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString()), "key", party, "acme", "ágil", null, "https://host/Logo-aB.png");
        var company = CompanyRegistration.candidate(command, Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID);
        var identifier = new PartyView.Identifier(UUID.randomUUID(), party, UUID.randomUUID(), "EC_RUC", "*********0001", "VERIFIED", true,
                "SRI", LocalDate.parse("2020-01-15"), null, now, "Verifier-aB", 2, now, now);
        var view = new PartyView(party, "LEGAL_ENTITY", "COMPAÑÍA", "ACTIVE", 3, now, now, "Party-actor", "Party-editor",
                new PartyView.LegalEntity("COMPAÑÍA S.A.S.", null, "SAS", "EC", LocalDate.parse("2020-01-15"), null), List.of(identifier));
        var result = new CompanyRegistration.Result(company, view); var codec = new AcceptedResultCodec();
        var document = codec.encode(result);
        // JSONB-compatible round trip changes Java storage types; the historical decoder restores them.
        var json = new ObjectMapper().findAndRegisterModules();
        Map<String,Object> stored = json.readValue(json.writeValueAsBytes(document), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        var replay = codec.decode((short)1, stored);
        assertEquals(result, replay);
        assertEquals(json.valueToTree(CompanyResponse.from(result)), json.valueToTree(CompanyResponse.from(replay)));
        assertThrows(IllegalStateException.class, () -> codec.decode((short)2, stored));
        assertThrows(IllegalStateException.class, () -> codec.decode((short)1, Map.of()));
        var corrupt = new LinkedHashMap<>(stored); corrupt.put("unexpected", "private-provider-payload");
        var failure = assertThrows(IllegalStateException.class, () -> codec.decode((short)1, corrupt));
        assertEquals("historical-result-invalid", failure.getMessage()); assertNull(failure.getCause());
    }
}
