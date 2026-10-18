package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.company.CompanyLifecycle;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class LifecycleFingerprintV1Test {
    private static final UUID TENANT = UUID.fromString("0199e000-0000-7000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("0199e000-0000-7000-8000-000000000002");
    private static final byte[] SECRET = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);

    private CompanyLifecycle.Command command(CompanyLifecycle.Operation operation) {
        return new CompanyLifecycle.Command(new OperationContext(TENANT, "Actor-aB", UUID.randomUUID().toString()), COMPANY, 3, "Key-aB", operation);
    }

    @Test
    void freezesCanonicalBytesAndIndependentHmacForEveryNamespace() throws Exception {
        for (var operation : CompanyLifecycle.Operation.values()) {
            var command = command(operation);
            String expected = "{\"fingerprintVersion\":1,\"tenantId\":\"" + TENANT + "\",\"operation\":\""
                    + operation.namespace() + "\",\"companyId\":\"" + COMPANY + "\",\"expectedVersion\":\"3\",\"request\":{}}";
            assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), LifecycleFingerprintV1.bytes(command));
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            assertEquals(HexFormat.of().formatHex(mac.doFinal(expected.getBytes(StandardCharsets.UTF_8))),
                    LifecycleFingerprintV1.sign(command, SECRET));
        }
    }

    @Test
    void bindsOnlyTenantOperationCompanyAndOriginalRevision() {
        var original = command(CompanyLifecycle.Operation.ACTIVATE);
        var hash = LifecycleFingerprintV1.sign(original, SECRET);
        for (var operation : new CompanyLifecycle.Operation[] {CompanyLifecycle.Operation.DEACTIVATE, CompanyLifecycle.Operation.ARCHIVE}) {
            assertFalse(LifecycleFingerprintV1.matches(command(operation), SECRET, hash));
        }
        assertFalse(LifecycleFingerprintV1.matches(new CompanyLifecycle.Command(original.context(), UUID.randomUUID(), 3, null, original.operation()), SECRET, hash));
        assertFalse(LifecycleFingerprintV1.matches(new CompanyLifecycle.Command(original.context(), COMPANY, 4, null, original.operation()), SECRET, hash));
        var otherTenant = new OperationContext(UUID.randomUUID(), "actor", UUID.randomUUID().toString());
        assertFalse(LifecycleFingerprintV1.matches(new CompanyLifecycle.Command(otherTenant, COMPANY, 3, null, original.operation()), SECRET, hash));
        var otherActor = new OperationContext(TENANT, "Other actor", UUID.randomUUID().toString());
        assertTrue(LifecycleFingerprintV1.matches(new CompanyLifecycle.Command(otherActor, COMPANY, 3, null, original.operation()), SECRET, hash));
        assertThrows(IllegalStateException.class, () -> LifecycleFingerprintV1.matches(original, SECRET, "bad"));
    }

    @Test
    void rotatesWithoutReplacingHistoricalMaterial() {
        var encoder = Base64.getEncoder();
        var keys = Map.of("K1", encoder.encodeToString(SECRET), "K2", encoder.encodeToString("98765432109876543210987654321098".getBytes(StandardCharsets.UTF_8)));
        var old = new ConfiguredLifecycleFingerprints(new HmacKeys("K1", keys));
        var rotated = new ConfiguredLifecycleFingerprints(new HmacKeys("K2", keys));
        for (var operation : CompanyLifecycle.Operation.values()) {
            var command = command(operation);
            var signature = old.sign(command);
            var hash = signature.hash();
            assertNotEquals(hash, rotated.sign(command).hash());
            assertTrue(rotated.matches(command, signature.version(), signature.keyId(), hash));
            assertThrows(IllegalStateException.class, () -> rotated.matches(command, (short) 2, "K1", hash));
            assertThrows(IllegalStateException.class, () -> rotated.matches(command, (short) 1, "missing", hash));
        }
    }
}
