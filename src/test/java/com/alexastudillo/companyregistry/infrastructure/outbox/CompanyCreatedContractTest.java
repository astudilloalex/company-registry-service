package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompanyCreatedContractTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void eventMatchesPublishedSchemaAndRejectsUnapprovedFields() throws Exception {
        var schema=SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("docs/contracts/events/company.created.v1.schema.json")),com.networknt.schema.InputFormat.JSON);
        var example=json.readTree(Files.readString(Path.of("docs/contracts/events/company.created.v1.example.json")));
        assertTrue(schema.validate(example).isEmpty());
        var ids=new UuidV7Generator();var now=Instant.parse("2026-10-17T14:30:00.123456Z");
        var context=new OperationContext(UUID.randomUUID(),"Actor-aB",UUID.randomUUID().toString());
        var command=new CompanyRegistration.Command(context,"Key-aB",UUID.randomUUID(),"acme","ágil","description","https://host/Logo.png");
        var row=CompanyRegistration.candidate(command,Clock.fixed(now,ZoneOffset.UTC),ids);
        var candidate=CompanyCreated.candidate(ids.next(),row,context);var outbox=CompanyCreatedOutbox.row(candidate);
        var event=json.valueToTree(CompanyCreatedOutbox.envelope(outbox));
        assertTrue(schema.validate(event).isEmpty(),()->schema.validate(event).toString());
        assertEquals(11,event.size()); assertEquals(7,event.path("payload").size());
        assertTrue(event.has("causationId"));assertTrue(event.path("causationId").isNull());
        assertEquals(event.path("aggregateId"),event.path("payload").path("companyId"));
        assertEquals(event.path("occurredAt"),event.path("payload").path("createdAt"));
        assertNotEquals(event.path("aggregateId"),event.path("eventId"));
        assertEquals(context.processId(),outbox.correlationId()); assertEquals(context.actor(),outbox.audit().createdBy());
        assertEquals(0,outbox.aggregateVersion()); assertEquals(1,outbox.schemaVersion()); assertEquals(0,outbox.version()); assertEquals(0,outbox.attempts());
        for(var field:List.of("party","identifiers","description","logoUrl")) {
            ObjectNode invalid=event.deepCopy();((ObjectNode)invalid.path("payload")).put(field,"forbidden");assertFalse(schema.validate(invalid).isEmpty());
        }
        ObjectNode wrong=event.deepCopy();wrong.put("eventSchemaVersion",2);assertFalse(schema.validate(wrong).isEmpty());
        try(var resource=getClass().getResourceAsStream("/META-INF/contracts/events/company.created.v1.schema.json")) { assertNotNull(resource); assertEquals(Files.readString(Path.of("docs/contracts/events/company.created.v1.schema.json")),new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
