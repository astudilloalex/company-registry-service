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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AddressAcceptanceTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final AddressScope SCOPE = new AddressScope(TENANT,
            UUID.fromString("019a2f00-0000-7000-8000-000000000001"), UUID.fromString("019a2f00-0000-7000-8000-000000000002"));
    private static final UUID ADDRESS = UUID.fromString("019a2f00-0000-7000-8000-000000000003");
    private static final OperationContext CONTEXT = new OperationContext(TENANT, "Actor-aB", "10000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-18T20:00:00.123456Z");
    private static final Map<String, String> KEYS = Map.of("K1", Base64.getEncoder().encodeToString(new byte[32]),
            "K2", Base64.getEncoder().encodeToString("second-address-key-material-32bytes".getBytes(StandardCharsets.UTF_8)));

    @Test
    void creationVectorMaterializesDefaultsAndUsesFixedOrder() {
        var command = command(AddressMutation.Operation.CREATE, createPatch(), 7);
        var bytes = new String(AddressFingerprintV1.bytes(command), StandardCharsets.UTF_8);
        assertEquals("{\"fingerprintVersion\":1,\"tenantId\":\"10000000-0000-4000-8000-000000000001\","
                + "\"operation\":\"company.site.address.create.v1\",\"companyId\":\"019a2f00-0000-7000-8000-000000000001\","
                + "\"siteId\":\"019a2f00-0000-7000-8000-000000000002\",\"addressId\":null,\"expectedVersion\":\"7\","
                + "\"request\":{\"addressType\":\"PHYSICAL\",\"countryCode\":\"EC\",\"addressLine1\":\"AVENIDA  JOSÉ\","
                + "\"administrativeDivisionCode\":null,\"addressLine2\":null,\"houseNumber\":null,\"postalCode\":null,"
                + "\"latitude\":null,\"longitude\":null,\"isPrimary\":false}}", bytes);
        assertEquals("1ec28201e4330677f1971ff9f7b53c19984c7387788926b2096d6d5a1aa1ac05",
                AddressFingerprintV1.sign(command, new byte[32]));
        var input = createPatch();
        var explicitDefaults = new AddressInformationPatch(input.addressType(), input.text(),
                new AddressInformationPatch.PrimaryChange(true, false), new AddressCoordinates.Change(true, null, true, null));
        assertEquals(AddressFingerprintV1.sign(command, new byte[32]), AddressFingerprintV1.sign(
                command(AddressMutation.Operation.CREATE, explicitDefaults, 7), new byte[32]));
    }

    @Test
    void updateSignsPresenceNumericEquivalenceAndOriginalRevisionButExcludesCaller() {
        var input = AddressInformationPatch.empty();
        var withCoordinates = new AddressInformationPatch(input.addressType(), input.text(), input.isPrimary(),
                new AddressCoordinates.Change(true, new BigDecimal("-0.1234560"), true, new BigDecimal("-0.00000000")));
        var equivalent = new AddressInformationPatch(input.addressType(), input.text(), input.isPrimary(),
                new AddressCoordinates.Change(true, new BigDecimal("-0.123456"), true, BigDecimal.ZERO));
        var command = command(AddressMutation.Operation.UPDATE, withCoordinates, 7);
        var signature = AddressFingerprintV1.sign(command, new byte[32]);
        assertEquals(signature, AddressFingerprintV1.sign(command(AddressMutation.Operation.UPDATE, equivalent, 7), new byte[32]));
        var otherCaller = new OperationContext(TENANT, "Other Actor", UUID.randomUUID().toString());
        assertEquals(signature, AddressFingerprintV1.sign(new AddressMutation.Command(otherCaller, SCOPE, 7, "other-key", command.intent()), new byte[32]));
        assertNotEquals(signature, AddressFingerprintV1.sign(command(AddressMutation.Operation.UPDATE, equivalent, 8), new byte[32]));
        var clear = new AddressInformationPatch(input.addressType(), input.text(), input.isPrimary(), new AddressCoordinates.Change(true, null, true, null));
        assertNotEquals(AddressFingerprintV1.sign(command(AddressMutation.Operation.UPDATE, input, 7), new byte[32]),
                AddressFingerprintV1.sign(command(AddressMutation.Operation.UPDATE, clear, 7), new byte[32]));
        assertTrue(new String(AddressFingerprintV1.bytes(command), StandardCharsets.UTF_8).contains("\"value\":\"0\""));
    }

    @Test
    void deletionSignsEmptyIntentAndDifferentHierarchyAndAddressCannotReplay() {
        var command = command(AddressMutation.Operation.DELETE, AddressInformationPatch.empty(), 7);
        assertTrue(new String(AddressFingerprintV1.bytes(command), StandardCharsets.UTF_8).endsWith("\"request\":{}}"));
        var hash = AddressFingerprintV1.sign(command, new byte[32]);
        assertEquals("e589b1b3363a146db23b73eb9a7f2e37d9e9d9baa00339b16e5fe7af9c5ea514", hash);
        assertEquals("4d747f3f24e40ca09af334da7bfd835378f8a28cfc3161ceaafe71b3a9f8365f",
                AddressFingerprintV1.sign(command(AddressMutation.Operation.UPDATE, AddressInformationPatch.empty(), 7), new byte[32]));
        var otherScope = new AddressScope(TENANT, UUID.randomUUID(), SCOPE.siteId());
        assertFalse(AddressFingerprintV1.matches(new AddressMutation.Command(CONTEXT, otherScope, 7, "key", command.intent()), new byte[32], hash));
        var otherAddress = new AddressMutation.Intent(AddressMutation.Operation.DELETE, UUID.randomUUID(), AddressInformationPatch.empty());
        assertFalse(AddressFingerprintV1.matches(new AddressMutation.Command(CONTEXT, SCOPE, 7, "key", otherAddress), new byte[32], hash));
    }

    @Test
    void keyRotationAndIndependentInstancesRetainOriginalPolicy() {
        var command = command(AddressMutation.Operation.CREATE, createPatch(), 7);
        var old = new ConfiguredAddressFingerprints(new HmacKeys("K1", KEYS));
        var active = new ConfiguredAddressFingerprints(new HmacKeys("K2", KEYS));
        var signature = old.sign(command);
        short version = signature.version();
        String keyId = signature.keyId();
        String hash = signature.hash();
        assertTrue(active.matches(command, version, keyId, hash));
        assertNotEquals(hash, active.sign(command).hash());
        assertThrows(IllegalStateException.class, () -> active.matches(command, (short) 2, "K1", hash));
        var missing = new ConfiguredAddressFingerprints(new HmacKeys("K2", Map.of("K2", KEYS.get("K2"))));
        assertThrows(IllegalStateException.class, () -> missing.matches(command, version, keyId, hash));
    }

    @Test
    void jsonbRoundTripRetainsNumericCoordinatesNullsOriginalAuditAndVersion() throws Exception {
        var codec = new AddressResultCodec();
        var result = view(8, NOW);
        var snapshot = jsonb(codec.encode(AddressMutation.Operation.CREATE, result));
        var stored = completion(AddressMutation.Operation.CREATE, 7, snapshot);
        var decoded = new AddressResultCodec().decode(stored, null);
        assertEquals(result, decoded);
        var row = (Map<?, ?>) ((Map<?, ?>) snapshot.get("result")).get("address");
        assertTrue(row.get("latitude") instanceof Number);
        assertTrue(row.containsKey("line2"));
        assertNull(row.get("line2"));
        var noOp = view(7, NOW.minusSeconds(1));
        var noOpStored = completion(AddressMutation.Operation.UPDATE, 7, jsonb(codec.encode(AddressMutation.Operation.UPDATE, noOp)));
        assertEquals(noOp, codec.decode(noOpStored, ADDRESS));
    }

    @Test
    void deletionSnapshotContainsOnlyOwnershipAndOriginalReceipt() throws Exception {
        var codec = new AddressResultCodec();
        var result = new AddressMutation.Deletion(SCOPE, ADDRESS, 8, NOW, CONTEXT.actor());
        var snapshot = jsonb(codec.encode(AddressMutation.Operation.DELETE, result));
        assertEquals(Set.of("scope", "addressId", "siteVersion", "deletedAt", "deletedBy"), ((Map<?, ?>) snapshot.get("result")).keySet());
        assertEquals(result, codec.decode(completion(AddressMutation.Operation.DELETE, 7, snapshot), ADDRESS));
    }

    @Test
    void rejectsCorruptOwnershipIdentityRevisionTypesAndUnknownFormatWithoutCauses() throws Exception {
        var codec = new AddressResultCodec();
        for (String field : new String[] {"siteVersion", "unknown", "scope", "address"}) {
            var snapshot = jsonb(codec.encode(AddressMutation.Operation.UPDATE, view(8, NOW)));
            var result = cast(snapshot.get("result"));
            result.put(field, "private-corruption");
            var stored = completion(AddressMutation.Operation.UPDATE, 7, snapshot);
            var failure = assertThrows(IllegalStateException.class, () -> codec.decode(stored, ADDRESS));
            assertEquals("historical-address-result-invalid", failure.getMessage());
            assertNull(failure.getCause());
        }
        var stored = completion(AddressMutation.Operation.UPDATE, 7, codec.encode(AddressMutation.Operation.UPDATE, view(8, NOW)));
        var wrongId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> codec.decode(stored, wrongId));
        var stale = completion(AddressMutation.Operation.UPDATE, 5, stored.snapshot());
        assertThrows(IllegalStateException.class, () -> codec.decode(stale, ADDRESS));
        var unknown = new CompletionRecord(stored.key(), stored.requestHash(), stored.companyId(), stored.siteId(), stored.expectedVersion(),
                stored.resultStatus(), (short) 2, stored.snapshot(), NOW, CONTEXT.actor(), (short) 1, "K1");
        assertThrows(IllegalStateException.class, () -> codec.decode(unknown, ADDRESS));
    }

    @Test
    void onlyCreationRequiresGeneratedUuidV7AndExistingUuidIdentitiesRemainEditable() throws Exception {
        var codec = new AddressResultCodec(); var original = view(8, NOW); var row = original.address();
        var legacyId = UUID.fromString("10000000-0000-4000-8000-000000000004");
        var legacy = new AddressMutation.View(SCOPE, new AddressRecord(legacyId, row.siteId(), row.type(), row.countryCode(),
                row.administrativeDivisionCode(), row.line1(), row.line2(), row.houseNumber(), row.postalCode(), row.latitude(), row.longitude(),
                row.primary(), row.audit(), null, null), 8);
        var update = completion(AddressMutation.Operation.UPDATE, 7, jsonb(codec.encode(AddressMutation.Operation.UPDATE, legacy)));
        assertEquals(legacy, codec.decode(update, legacyId));
        var deletion = new AddressMutation.Deletion(SCOPE, legacyId, 8, NOW, CONTEXT.actor());
        var removed = completion(AddressMutation.Operation.DELETE, 7, jsonb(codec.encode(AddressMutation.Operation.DELETE, deletion)));
        assertEquals(deletion, codec.decode(removed, legacyId));
        assertThrows(IllegalStateException.class, () -> codec.encode(AddressMutation.Operation.CREATE, legacy));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }

    private static Map<String, Object> jsonb(Map<String, Object> value) throws Exception {
        var mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<Map<String, Object>>() {});
    }

    private static AddressMutation.View view(long version, Instant updated) {
        return new AddressMutation.View(SCOPE, new AddressRecord(ADDRESS, SCOPE.siteId(), AddressType.PHYSICAL, "EC", null,
                "AVENIDA  JOSÉ", null, null, null, new BigDecimal("-0.123456"), new BigDecimal("78.123456"), false,
                new Audit(updated, CONTEXT.actor(), updated, CONTEXT.actor()), null, null), version);
    }

    private static CompletionRecord completion(AddressMutation.Operation operation, long expected, Map<String, Object> snapshot) {
        return new CompletionRecord(new CompletionKey(TENANT, operation.namespace(), "key"), "a".repeat(64), SCOPE.companyId(), SCOPE.siteId(),
                expected, operation.resultStatus(), (short) 1, snapshot, NOW, CONTEXT.actor(), (short) 1, "K1");
    }

    private static AddressMutation.Command command(AddressMutation.Operation operation, AddressInformationPatch patch, long expected) {
        return new AddressMutation.Command(CONTEXT, SCOPE, expected, "key",
                new AddressMutation.Intent(operation, operation == AddressMutation.Operation.CREATE ? null : ADDRESS, patch));
    }

    private static AddressInformationPatch createPatch() {
        var empty = AddressInformationPatch.empty();
        return new AddressInformationPatch(FieldChange.present(" physical "), new AddressInformationPatch.TextChanges(
                FieldChange.present(" ec "), FieldChange.present(" avenida  josé "), FieldChange.absent(), FieldChange.absent(),
                FieldChange.absent(), FieldChange.absent()), empty.isPrimary(), empty.coordinates());
    }
}
