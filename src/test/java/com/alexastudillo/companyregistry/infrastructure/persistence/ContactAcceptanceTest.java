package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContactAcceptanceTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final ContactScope SCOPE = new ContactScope(TENANT,
            UUID.fromString("019a2f00-0000-7000-8000-000000000001"),
            UUID.fromString("019a2f00-0000-7000-8000-000000000002"));
    private static final UUID CONTACT = UUID.fromString("019a2f00-0000-7000-8000-000000000003");
    private static final OperationContext CONTEXT = new OperationContext(TENANT, "Actor-aB",
            "10000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-18T20:00:00.123456Z");
    private static final Map<String, String> KEYS = Map.of("K1", Base64.getEncoder().encodeToString(new byte[32]),
            "K2",
            Base64.getEncoder().encodeToString("second-contact-key-material-32bytes".getBytes(StandardCharsets.UTF_8)));

    @Test
    void creationVectorMaterializesDefaultsAndUsesFixedOrder() {
        var command = command(ContactMutation.Operation.CREATE, createPatch(), 7);
        assertEquals(
                "{\"fingerprintVersion\":1,\"tenantId\":\"10000000-0000-4000-8000-000000000001\",\"operation\":\"company.site.contact.create.v1\",\"companyId\":\"019a2f00-0000-7000-8000-000000000001\",\"siteId\":\"019a2f00-0000-7000-8000-000000000002\",\"contactId\":null,\"expectedVersion\":\"7\",\"request\":{\"contactType\":\"EMAIL\",\"valueFamily\":\"EMAIL\",\"value\":\"a@example.com\",\"label\":null,\"isPrimary\":false}}",
                new String(ContactFingerprintV1.bytes(command), StandardCharsets.UTF_8));
        assertEquals("111b517b79cc7200854f7b163a930c45e0d34ff690b3bdd052689ed58b7ca2b1",
                ContactFingerprintV1.sign(command, new byte[32]));
        var input = createPatch();
        var explicit = new ContactInformationPatch(input.contactType(), input.value(), FieldChange.present(null),
                new ContactInformationPatch.PrimaryChange(true, false));
        assertEquals(ContactFingerprintV1.sign(command, new byte[32]), ContactFingerprintV1.sign(
                command(ContactMutation.Operation.CREATE, explicit, 7), new byte[32]));
    }

    @Test
    void valueOnlyIntentionPreservesPresenceAndExcludesCallerAndIdempotencyKey() {
        var empty = ContactInformationPatch.empty();
        var input = new ContactInformationPatch(empty.contactType(), FieldChange.present(" A@Example.com "),
                empty.label(), empty.isPrimary());
        var equivalent = new ContactInformationPatch(empty.contactType(), FieldChange.present("a@example.com"),
                empty.label(), empty.isPrimary());
        var original = command(ContactMutation.Operation.UPDATE, input, 7);
        var signature = ContactFingerprintV1.sign(original, new byte[32]);
        assertEquals(signature,
                ContactFingerprintV1.sign(command(ContactMutation.Operation.UPDATE, equivalent, 7), new byte[32]));
        var caller = new OperationContext(TENANT, "Different Actor", UUID.randomUUID().toString());
        assertEquals(signature, ContactFingerprintV1
                .sign(new ContactMutation.Command(caller, SCOPE, 7, "other-key", original.intent()), new byte[32]));
        assertNotEquals(signature,
                ContactFingerprintV1.sign(command(ContactMutation.Operation.UPDATE, equivalent, 8), new byte[32]));
        var explicitType = new ContactInformationPatch(FieldChange.present("EMAIL"), input.value(), empty.label(),
                empty.isPrimary());
        assertNotEquals(signature,
                ContactFingerprintV1.sign(command(ContactMutation.Operation.UPDATE, explicitType, 7), new byte[32]));
        var phone = new ContactInformationPatch(empty.contactType(), FieldChange.present("+1 (202) 555-0123"),
                empty.label(), empty.isPrimary());
        var e164 = new ContactInformationPatch(empty.contactType(), FieldChange.present("+12025550123"), empty.label(),
                empty.isPrimary());
        assertEquals(ContactFingerprintV1.sign(command(ContactMutation.Operation.UPDATE, phone, 7), new byte[32]),
                ContactFingerprintV1.sign(command(ContactMutation.Operation.UPDATE, e164, 7), new byte[32]));
    }

    @Test
    void resourceAndRouteIdentityAreSignedAndResultRevisionCannotOverflow() {
        var original = command(ContactMutation.Operation.UPDATE, ContactInformationPatch.empty(), 7);
        var hash = ContactFingerprintV1.sign(original, new byte[32]);
        var otherScope = new ContactScope(TENANT, UUID.randomUUID(), SCOPE.siteId());
        var moved = new ContactMutation.Command(CONTEXT, otherScope, 7, "key", original.intent());
        assertFalse(ContactFingerprintV1.matches(moved, new byte[32], hash));
        var otherId = new ContactMutation.Intent(ContactMutation.Operation.UPDATE, UUID.randomUUID(),
                ContactInformationPatch.empty());
        var other = new ContactMutation.Command(CONTEXT, SCOPE, 7, "key", otherId);
        assertFalse(ContactFingerprintV1.matches(other, new byte[32], hash));
        var codec = new ContactResultCodec();
        var snapshot = codec.encode(ContactMutation.Operation.UPDATE, view(0, NOW));
        var overflow = completion(ContactMutation.Operation.UPDATE, Long.MAX_VALUE, snapshot);
        assertThrows(IllegalStateException.class, () -> codec.decode(overflow, CONTACT));
        var unchanged = view(Long.MAX_VALUE, NOW.minusSeconds(1));
        var noOp = completion(ContactMutation.Operation.UPDATE, Long.MAX_VALUE,
                codec.encode(ContactMutation.Operation.UPDATE, unchanged));
        assertEquals(unchanged, codec.decode(noOp, CONTACT));
    }

    @Test
    void creationSigningCanonicalizesFamiliesBeforeEffectiveChannelAdmission() {
        var empty = ContactInformationPatch.empty();
        var fixedMobile = new ContactInformationPatch(FieldChange.present("MOBILE"),
                FieldChange.present("+44 20 7946 0018"),
                empty.label(), empty.isPrimary());
        var command = command(ContactMutation.Operation.CREATE, fixedMobile, 7);
        var bytes = new String(ContactFingerprintV1.bytes(command), StandardCharsets.UTF_8);
        assertTrue(bytes.contains("\"valueFamily\":\"PHONE\""));
        assertTrue(bytes.contains("\"value\":\"+442079460018\""));
        var incompatible = new ContactInformationPatch(FieldChange.present("PHONE"),
                FieldChange.present("a@example.com"),
                empty.label(), empty.isPrimary());
        var other = command(ContactMutation.Operation.CREATE, incompatible, 7);
        assertTrue(new String(ContactFingerprintV1.bytes(other), StandardCharsets.UTF_8)
                .contains("\"valueFamily\":\"EMAIL\""));
    }

    @Test
    void keyRotationAndIndependentInstancesRetainOriginalPolicy() {
        var command = command(ContactMutation.Operation.CREATE, createPatch(), 7);
        var old = new ConfiguredContactFingerprints(new HmacKeys("K1", KEYS));
        var active = new ConfiguredContactFingerprints(new HmacKeys("K2", KEYS));
        var signature = old.sign(command);
        short version = signature.version();
        String keyId = signature.keyId();
        String hash = signature.hash();
        assertTrue(active.matches(command, version, keyId, hash));
        assertNotEquals(hash, active.sign(command).hash());
        assertThrows(IllegalStateException.class, () -> active.matches(command, (short) 2, "K1", hash));
        var missing = new ConfiguredContactFingerprints(new HmacKeys("K2", Map.of("K2", KEYS.get("K2"))));
        assertThrows(IllegalStateException.class, () -> missing.matches(command, version, keyId, hash));
    }

    @Test
    void jsonRoundTripRetainsNullLabelOriginalAuditAndVersion() throws Exception {
        var codec = new ContactResultCodec();
        var result = view(8, NOW);
        var snapshot = jsonb(codec.encode(ContactMutation.Operation.CREATE, result));
        var stored = completion(ContactMutation.Operation.CREATE, 7, snapshot);
        var decoded = new ContactResultCodec().decode(stored, null);
        assertEquals(result, decoded);
        var row = (Map<?, ?>) ((Map<?, ?>) snapshot.get("result")).get("contact");
        assertTrue(row.containsKey("label"));
        assertNull(row.get("label"));
        var noOp = view(7, NOW.minusSeconds(1));
        var noOpStored = completion(ContactMutation.Operation.UPDATE, 7,
                jsonb(codec.encode(ContactMutation.Operation.UPDATE, noOp)));
        assertEquals(noOp, codec.decode(noOpStored, CONTACT));
    }

    @Test
    void deletionSnapshotContainsOnlyOwnershipAndOriginalReceipt() throws Exception {
        var codec = new ContactResultCodec();
        var result = new ContactMutation.Deletion(SCOPE, CONTACT, 8, NOW, CONTEXT.actor());
        var snapshot = jsonb(codec.encode(ContactMutation.Operation.DELETE, result));
        assertEquals(Set.of("scope", "contactId", "siteVersion", "deletedAt", "deletedBy"),
                ((Map<?, ?>) snapshot.get("result")).keySet());
        assertEquals(result, codec.decode(completion(ContactMutation.Operation.DELETE, 7, snapshot), CONTACT));
    }

    @Test
    void rejectsCorruptOwnershipIdentityRevisionTypesAndUnknownFormatWithoutCauses() throws Exception {
        var codec = new ContactResultCodec();
        for (String field : new String[] { "siteVersion", "unknown", "scope", "contact" }) {
            var snapshot = jsonb(codec.encode(ContactMutation.Operation.UPDATE, view(8, NOW)));
            var result = cast(snapshot.get("result"));
            result.put(field, "private-corruption");
            var stored = completion(ContactMutation.Operation.UPDATE, 7, snapshot);
            var failure = assertThrows(IllegalStateException.class, () -> codec.decode(stored, CONTACT));
            assertEquals("historical-contact-result-invalid", failure.getMessage());
            assertNull(failure.getCause());
        }
        var stored = completion(ContactMutation.Operation.UPDATE, 7,
                codec.encode(ContactMutation.Operation.UPDATE, view(8, NOW)));
        var wrongId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> codec.decode(stored, wrongId));
        var stale = completion(ContactMutation.Operation.UPDATE, 5, stored.snapshot());
        assertThrows(IllegalStateException.class, () -> codec.decode(stale, CONTACT));
        var unknown = new CompletionRecord(stored.key(), stored.requestHash(), stored.companyId(), stored.siteId(),
                stored.expectedVersion(),
                stored.resultStatus(), (short) 2, stored.snapshot(), NOW, CONTEXT.actor(), (short) 1, "K1");
        assertThrows(IllegalStateException.class, () -> codec.decode(unknown, CONTACT));
    }

    @Test
    void onlyCreationRequiresGeneratedUuidV7AndExistingUuidIdentitiesRemainEditable() throws Exception {
        var codec = new ContactResultCodec();
        var original = view(8, NOW);
        var row = original.contact();
        var legacyId = UUID.fromString("10000000-0000-4000-8000-000000000004");
        var legacy = new ContactMutation.View(SCOPE,
                new ContactRecord(legacyId, row.siteId(), row.type(), row.value(), row.label(),
                        row.primary(), row.audit(), null, null),
                8);
        var update = completion(ContactMutation.Operation.UPDATE, 7,
                jsonb(codec.encode(ContactMutation.Operation.UPDATE, legacy)));
        assertEquals(legacy, codec.decode(update, legacyId));
        var deletion = new ContactMutation.Deletion(SCOPE, legacyId, 8, NOW, CONTEXT.actor());
        var removed = completion(ContactMutation.Operation.DELETE, 7,
                jsonb(codec.encode(ContactMutation.Operation.DELETE, deletion)));
        assertEquals(deletion, codec.decode(removed, legacyId));
        assertThrows(IllegalStateException.class, () -> codec.encode(ContactMutation.Operation.CREATE, legacy));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> jsonb(Map<String, Object> value) throws Exception {
        var mapper = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<Map<String, Object>>() {
        });
    }

    private static ContactMutation.View view(long version, Instant updated) {
        return new ContactMutation.View(SCOPE,
                new ContactRecord(CONTACT, SCOPE.siteId(), ContactType.EMAIL, "a@example.com", null, false,
                        new Audit(updated, CONTEXT.actor(), updated, CONTEXT.actor()), null, null),
                version);
    }

    private static CompletionRecord completion(ContactMutation.Operation operation, long expected,
            Map<String, Object> snapshot) {
        return new CompletionRecord(new CompletionKey(TENANT, operation.namespace(), "key"), "a".repeat(64),
                SCOPE.companyId(), SCOPE.siteId(),
                expected, operation.resultStatus(), (short) 1, snapshot, NOW, CONTEXT.actor(), (short) 1, "K1");
    }

    private static ContactMutation.Command command(ContactMutation.Operation operation, ContactInformationPatch patch,
            long expected) {
        return new ContactMutation.Command(CONTEXT, SCOPE, expected, "key",
                new ContactMutation.Intent(operation, operation == ContactMutation.Operation.CREATE ? null : CONTACT,
                        patch));
    }

    private static ContactInformationPatch createPatch() {
        var empty = ContactInformationPatch.empty();
        return new ContactInformationPatch(FieldChange.present(" email "), FieldChange.present(" A@Example.COM "),
                FieldChange.absent(), empty.isPrimary());
    }
}
