package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SiteUpdatedContractTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void producerRejectsCrossFieldInvariantDiscrepancies() {
        var ids = new UuidV7Generator();
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var registered = SiteRegistration.candidate(new SiteRegistration.Command(context, "key", ids.next(), "x", "y",
                com.alexastudillo.companyregistry.domain.model.FieldChange.absent()), Clock.systemUTC(), ids);
        var row = mutated(registered);
        var event = SiteMutated.candidate(ids.next(), row, context);
        var mismatchedAggregate = new SiteMutated(event.eventId(),
                event.tenantId(), event.aggregateType(), ids.next(), event.aggregateVersion(), event.eventType(),
                event.eventSchemaVersion(), event.occurredAt(), event.correlationId(), null, event.payload());
        assertThrows(IllegalArgumentException.class, () -> SiteMutatedOutbox.row(mismatchedAggregate));

        var mismatchedOccurred = new SiteMutated(event.eventId(),
                event.tenantId(), event.aggregateType(), event.aggregateId(), event.aggregateVersion(), event.eventType(),
                event.eventSchemaVersion(), event.occurredAt().plusSeconds(1), event.correlationId(), null, event.payload());
        assertThrows(IllegalArgumentException.class, () -> SiteMutatedOutbox.row(mismatchedOccurred));

        var siteId = row.site().id();
        assertThrows(IllegalArgumentException.class, () -> SiteMutated.candidate(siteId, row, context));
    }
    @Test void publishedContractExamplesMatchSchema() throws Exception {
        var schema=SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("docs/contracts/events/company.site.updated.v1.schema.json")),com.networknt.schema.InputFormat.JSON);
        var example=json.readTree(Files.readString(Path.of("docs/contracts/events/company.site.updated.v1.example.json")));
        assertTrue(schema.validate(example).isEmpty());
        var child = json.readTree(Files.readString(Path.of("docs/contracts/events/company.site.updated.v1.child.example.json")));
        assertTrue(schema.validate(child).isEmpty());
        assertEquals(2, child.path("aggregateVersion").asInt());
        for (var field : List.of("siteId", "companyId", "code", "name", "siteType", "status")) {
            assertEquals(example.path("payload").path(field), child.path("payload").path(field));
        }
    }
    @Test void eventMatchesPublishedSchemaAndRejectsUnapprovedFields() throws Exception {
        var schema=SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("docs/contracts/events/company.site.updated.v1.schema.json")),com.networknt.schema.InputFormat.JSON);
        var context=new OperationContext(UUID.randomUUID(),"Actor-aB",UUID.randomUUID().toString());
        var candidate=sampleCandidate(context);var outbox=SiteMutatedOutbox.row(candidate);
        var event=json.valueToTree(SiteMutatedOutbox.envelope(outbox));
        assertTrue(schema.validate(event).isEmpty(),()->schema.validate(event).toString());
        for(var field:List.of("party","identifiers","description","logoUrl","effectivelyOperational","changedFields","address","contact")) {
            ObjectNode invalid=event.deepCopy();((ObjectNode)invalid.path("payload")).put(field,"forbidden");assertFalse(schema.validate(invalid).isEmpty());
        }
        ObjectNode wrongStatus=event.deepCopy();((ObjectNode)wrongStatus.path("payload")).put("status","CLOSED");assertFalse(schema.validate(wrongStatus).isEmpty());
        ObjectNode wrong=event.deepCopy();wrong.put("eventSchemaVersion",2);assertFalse(schema.validate(wrong).isEmpty());
        try(var resource=getClass().getResourceAsStream("/META-INF/contracts/events/company.site.updated.v1.schema.json")) { assertNotNull(resource); assertEquals(Files.readString(Path.of("docs/contracts/events/company.site.updated.v1.schema.json")),new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
    }

    @Test void outboxEnvelopeMapsCandidatePropertiesCorrectly() {
        var context=new OperationContext(UUID.randomUUID(),"Actor-aB",UUID.randomUUID().toString());
        var candidate=sampleCandidate(context);var outbox=SiteMutatedOutbox.row(candidate);
        var event=json.valueToTree(SiteMutatedOutbox.envelope(outbox));
        assertEquals(11,event.size()); assertEquals(8,event.path("payload").size());
        assertTrue(event.has("causationId"));assertTrue(event.path("causationId").isNull());
        assertEquals(event.path("aggregateId"),event.path("payload").path("siteId"));
        assertEquals(event.path("occurredAt"),event.path("payload").path("updatedAt"));
        assertNotEquals(event.path("aggregateId"),event.path("eventId"));
        assertEquals(context.processId(),outbox.correlationId()); assertEquals(context.actor(),outbox.audit().createdBy());
        assertEquals(1,outbox.aggregateVersion()); assertEquals(1,outbox.schemaVersion()); assertEquals(0,outbox.version()); assertEquals(0,outbox.attempts());
    }

    private SiteMutated sampleCandidate(OperationContext context) {
        var ids=new UuidV7Generator();var now=Instant.parse("2026-10-17T14:30:00.123456Z");
        var command=new SiteRegistration.Command(context,"Key-aB",ids.next(),"acme","ágil",com.alexastudillo.companyregistry.domain.model.FieldChange.absent());
        var registered = SiteRegistration.candidate(command,Clock.fixed(now,ZoneOffset.UTC),ids);
        var row = mutated(registered);
        return SiteMutated.candidate(ids.next(),row,context);
    }
    private com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord mutated(
            com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord row) {
        var site = row.site();
        return new com.alexastudillo.companyregistry.application.persistence.RegistryData.SiteRecord(
                new com.alexastudillo.companyregistry.domain.model.Site(site.id(), site.tenantId(), site.companyId(), site.type(),
                        com.alexastudillo.companyregistry.domain.model.SiteStatus.ACTIVE, site.version().next()),
                row.code(), row.name(), row.audit());
    }
}
