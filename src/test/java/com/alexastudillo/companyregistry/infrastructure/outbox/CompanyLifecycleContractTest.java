package com.alexastudillo.companyregistry.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyLifecycleContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UuidV7Generator ids = new UuidV7Generator();
    private final OperationContext context = new OperationContext(UUID.randomUUID(), "Actor-aB", UUID.randomUUID().toString());

    private CompanyRecord result(CompanyStatus status) {
        var now = Instant.parse("2026-10-18T12:00:00.123456Z");
        return new CompanyRecord(new Company(ids.next(), context.tenantId(), UUID.randomUUID(), status,
                new AggregateVersion(1)), "CODE", "COMPANY", null, null, new Audit(now, context.actor(), now, context.actor()));
    }

    private OutboxRecord event(CompanyLifecycle.Operation operation) {
        var row = result(operation.target());
        return operation == CompanyLifecycle.Operation.ARCHIVE
                ? CompanyUpdatedOutbox.row(CompanyUpdated.archivedCandidate(ids.next(), row, context))
                : CompanyLifecycleOutbox.row(CompanyLifecycleEvent.candidate(ids.next(), row, context, operation));
    }

    @Test
    void validatesEveryRealProducerAndPackagedExampleAgainstItsClosedSchema() throws Exception {
        for (var operation : CompanyLifecycle.Operation.values()) {
            var outbox = event(operation);
            String schemaName = outbox.eventType() + ".schema.json";
            String source = Files.readString(Path.of("docs/contracts/events", schemaName));
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(source, com.networknt.schema.InputFormat.JSON);
            var actual = (ObjectNode) json.valueToTree(CompanyUpdatedOutbox.envelope(outbox));
            assertTrue(schema.validate(actual).isEmpty(), () -> schema.validate(actual).toString());
            String exampleName = outbox.eventType() + (operation == CompanyLifecycle.Operation.ARCHIVE ? ".archived" : "") + ".example.json";
            var example = json.readTree(Files.readString(Path.of("docs/contracts/events", exampleName)));
            assertTrue(schema.validate(example).isEmpty());
            for (String field : new String[] {"party", "site", "description", "logoUrl", "idempotencyKey"}) {
                ObjectNode invalid = actual.deepCopy();
                ((ObjectNode) invalid.path("payload")).put(field, "forbidden");
                assertFalse(schema.validate(invalid).isEmpty());
            }
            if (operation != CompanyLifecycle.Operation.ARCHIVE) {
                ObjectNode invalid = actual.deepCopy();
                ((ObjectNode) invalid.path("payload")).put("status", "ARCHIVED");
                assertFalse(schema.validate(invalid).isEmpty());
            }
            for (String name : new String[] {schemaName, exampleName}) {
                try (var resource = getClass().getResourceAsStream("/META-INF/contracts/events/" + name)) {
                    assertNotNull(resource);
                    assertEquals(Files.readString(Path.of("docs/contracts/events", name)), new String(resource.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Test
    void preservesIdentityVersionAuditContextAndPendingTechnicalState() {
        for (var operation : CompanyLifecycle.Operation.values()) {
            var outbox = event(operation);
            var envelope = json.valueToTree(CompanyUpdatedOutbox.envelope(outbox));
            assertEquals(11, envelope.size());
            assertEquals(7, envelope.path("payload").size());
            assertEquals(envelope.path("aggregateId"), envelope.path("payload").path("companyId"));
            assertEquals(envelope.path("occurredAt"), envelope.path("payload").path("updatedAt"));
            assertEquals(operation.target().name(), envelope.path("payload").path("status").asText());
            assertEquals(7, outbox.id().version());
            assertNotEquals(outbox.aggregateId(), outbox.id());
            assertEquals(context.tenantId(), outbox.tenantId());
            assertEquals(context.processId(), outbox.correlationId());
            assertEquals(context.actor(), outbox.audit().updatedBy());
            assertEquals(1, outbox.aggregateVersion());
            assertEquals(0, outbox.version());
            assertEquals(0, outbox.attempts());
            assertEquals(OutboxStatus.PENDING, outbox.status());
            assertTrue(envelope.path("causationId").isNull());
        }
    }

    @Test
    void archivalFactoryDoesNotWeakenTheInformationUpdateProducer() {
        var archived = result(CompanyStatus.ARCHIVED);
        var eventId = ids.next();
        assertThrows(IllegalArgumentException.class, () -> CompanyUpdated.candidate(eventId, archived, context));
        assertEquals("ARCHIVED", CompanyUpdated.archivedCandidate(eventId, archived, context).payload().status());
        for (var status : new CompanyStatus[] {CompanyStatus.DRAFT, CompanyStatus.ACTIVE, CompanyStatus.INACTIVE}) {
            var editable = result(status);
            assertThrows(IllegalArgumentException.class, () -> CompanyUpdated.archivedCandidate(eventId, editable, context));
            assertEquals(status.name(), CompanyUpdated.candidate(eventId, editable, context).payload().status());
        }
    }

    @Test
    void rejectsIncompatibleFactsAndContext() {
        var row = result(CompanyStatus.ACTIVE);
        var eventId = ids.next();
        assertThrows(IllegalArgumentException.class, () -> CompanyLifecycleEvent.candidate(eventId, row, context, CompanyLifecycle.Operation.DEACTIVATE));
        assertThrows(IllegalArgumentException.class, () -> CompanyLifecycleEvent.candidate(eventId, row, context, CompanyLifecycle.Operation.ARCHIVE));
        var foreign = new OperationContext(UUID.randomUUID(), context.actor(), context.processId());
        assertThrows(IllegalArgumentException.class, () -> CompanyLifecycleEvent.candidate(eventId, row, foreign, CompanyLifecycle.Operation.ACTIVATE));
        var accepted = CompanyLifecycleEvent.candidate(eventId, row, context, CompanyLifecycle.Operation.ACTIVATE);
        var payload = accepted.payload();
        var otherId = ids.next();
        var now = accepted.occurredAt();
        var tenant = context.tenantId();
        var process = context.processId();
        assertThrows(IllegalArgumentException.class, () -> new CompanyLifecycleEvent(eventId, tenant, "COMPANY", otherId,
                1, "company.activated.v1", (short) 1, now, process, null, payload));
        var later = now.plusNanos(1000);
        var company = row.company().id();
        assertThrows(IllegalArgumentException.class, () -> new CompanyLifecycleEvent(eventId, tenant, "COMPANY", company,
                1, "company.activated.v1", (short) 1, later, process, null, payload));
    }
}
