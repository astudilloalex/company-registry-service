package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import com.alexastudillo.companyregistry.domain.model.AggregateVersion;
import com.alexastudillo.companyregistry.domain.model.Company;
import com.alexastudillo.companyregistry.domain.model.CompanyStatus;
import com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyUpdatedContractTest {
    private static final Instant NOW = Instant.parse("2026-10-17T14:30:00.123456Z");
    private final ObjectMapper json = new ObjectMapper();

    private Schema schema() throws Exception {
        return SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("docs/contracts/events/company.updated.v1.schema.json")), com.networknt.schema.InputFormat.JSON);
    }

    private CompanyUpdated sampleCandidate(OperationContext context) {
        var ids = new UuidV7Generator();
        var command = new CompanyRegistration.Command(context, "Key-aB", UUID.randomUUID(), "acme", "ágil", "description", "https://host/Logo.png");
        var original = CompanyRegistration.candidate(command, Clock.fixed(NOW, ZoneOffset.UTC), ids);
        var row = new CompanyRecord(
                new Company(original.company().id(), context.tenantId(), command.partyId(),
                        CompanyStatus.ACTIVE, new AggregateVersion(1)),
                original.code(), original.operatingName(), original.description(), original.logoUrl(), original.audit());
        return CompanyUpdated.candidate(ids.next(), row, context);
    }

    @Test
    void eventMatchesPublishedSchemaAndRejectsUnapprovedFields() throws Exception {
        var schema = schema();
        var example = json.readTree(Files.readString(Path.of("docs/contracts/events/company.updated.v1.example.json")));
        assertTrue(schema.validate(example).isEmpty());

        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var candidate = sampleCandidate(context);
        var outbox = CompanyUpdatedOutbox.row(candidate);
        var event = (ObjectNode) json.valueToTree(CompanyUpdatedOutbox.envelope(outbox));
        assertTrue(schema.validate(event).isEmpty(), () -> schema.validate(event).toString());

        for (var field : List.of("party", "identifiers", "description", "logoUrl")) {
            ObjectNode invalid = event.deepCopy();
            ((ObjectNode) invalid.path("payload")).put(field, "forbidden");
            assertFalse(schema.validate(invalid).isEmpty());
        }

        ObjectNode wrong = event.deepCopy();
        wrong.put("eventSchemaVersion", 2);
        assertFalse(schema.validate(wrong).isEmpty());

        try (var resource = getClass().getResourceAsStream("/META-INF/contracts/events/company.updated.v1.schema.json")) {
            assertNotNull(resource);
            assertEquals(Files.readString(Path.of("docs/contracts/events/company.updated.v1.schema.json")),
                    new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void outboxEnvelopeMapsCandidatePropertiesCorrectly() {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var candidate = sampleCandidate(context);
        var outbox = CompanyUpdatedOutbox.row(candidate);
        var event = json.valueToTree(CompanyUpdatedOutbox.envelope(outbox));

        assertEquals(11, event.size());
        assertEquals(7, event.path("payload").size());
        assertTrue(event.has("causationId"));
        assertTrue(event.path("causationId").isNull());
        assertEquals(event.path("aggregateId"), event.path("payload").path("companyId"));
        assertEquals(event.path("occurredAt"), event.path("payload").path("updatedAt"));
        assertNotEquals(event.path("aggregateId"), event.path("eventId"));
        assertEquals(context.processId(), outbox.correlationId());
        assertEquals(context.actor(), outbox.audit().updatedBy());
        assertEquals(1, outbox.aggregateVersion());
        assertEquals(1, outbox.schemaVersion());
        assertEquals(0, outbox.version());
        assertEquals(0, outbox.attempts());
    }

    @Test
    void schemaAcceptsApprovedStatuses() throws Exception {
        var schema = schema();
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var candidate = sampleCandidate(context);
        var outbox = CompanyUpdatedOutbox.row(candidate);
        var event = (ObjectNode) json.valueToTree(CompanyUpdatedOutbox.envelope(outbox));

        for (var status : List.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED")) {
            ObjectNode state = event.deepCopy();
            ((ObjectNode) state.path("payload")).put("status", status);
            assertTrue(schema.validate(state).isEmpty());
        }
    }

    @Test
    void rejectsMismatchedPayloadReferences() {
        var context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());
        var candidate = sampleCandidate(context);

        var eventId = candidate.eventId();
        var tenantId = candidate.tenantId();
        var correlationId = candidate.correlationId();
        var payload = candidate.payload();
        var mismatchedAggregateId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new CompanyUpdated(eventId, tenantId, "COMPANY", mismatchedAggregateId, 1,
                "company.updated.v1", (short) 1, NOW, correlationId, null, payload));

        var aggregateId = candidate.aggregateId();
        var mismatchedOccurredAt = NOW.plusSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new CompanyUpdated(eventId, tenantId, "COMPANY", aggregateId, 1,
                "company.updated.v1", (short) 1, mismatchedOccurredAt, correlationId, null, payload));
    }
}
