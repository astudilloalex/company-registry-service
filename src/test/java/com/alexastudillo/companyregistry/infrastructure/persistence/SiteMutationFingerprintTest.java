package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.site.SiteMutation;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import com.alexastudillo.companyregistry.domain.model.SiteInformationPatch;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SiteMutationFingerprintTest {
    private final UUID tenant = UUID.fromString("018f1234-5678-7000-8000-000000000001");
    private final UUID company = UUID.fromString("018f1234-5678-7000-8000-000000000004");
    private final UUID site = UUID.fromString("018f1234-5678-7000-8000-000000000005");
    private final byte[] secret = new byte[32];

    SiteMutationFingerprintTest() {
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) i;
    }

    private SiteMutation.Command command(SiteMutation.Operation operation, SiteInformationPatch patch) {
        return command(tenant, company, site, 7, operation, patch);
    }

    private SiteMutation.Command command(UUID tenantId, UUID companyId, UUID siteId, long version,
            SiteMutation.Operation operation, SiteInformationPatch patch) {
        return new SiteMutation.Command(new OperationContext(tenantId, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                companyId, siteId, version, UUID.randomUUID().toString(), operation, patch);
    }

    @Test
    void matchesIndependentUtf8AndHmacVectorsForEveryOperation() {
        var patch = new SiteInformationPatch(FieldChange.present(" ß "), FieldChange.present(" compañía  ágil "), FieldChange.absent());
        var expected = "{\"fingerprintVersion\":1,\"tenantId\":\"018f1234-5678-7000-8000-000000000001\",\"operation\":\"company.site.update.v1\",\"companyId\":\"018f1234-5678-7000-8000-000000000004\",\"siteId\":\"018f1234-5678-7000-8000-000000000005\",\"expectedVersion\":\"7\",\"request\":{\"code\":{\"present\":true,\"value\":\"SS\"},\"name\":{\"present\":true,\"value\":\"COMPAÑÍA  ÁGIL\"},\"siteType\":{\"present\":false}}}";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), SiteMutationFingerprintV1.bytes(command(SiteMutation.Operation.UPDATE, patch)));
        var hashes = Map.of(SiteMutation.Operation.UPDATE, "6ecda24fd3e9f51cb2c4d3a432c43c38724ae5dca2338eee1540f66f8020cb67",
                SiteMutation.Operation.ACTIVATE, "ed73fa76f3aa13455f3dc3ff7daaee3e5d119202bcb5cf6a5ca59b9a946767a2",
                SiteMutation.Operation.DEACTIVATE, "10cf0cbb07ec19c17ec46541fecd997500b6379b4c79ff9ee1c19176f17f33c0",
                SiteMutation.Operation.CLOSE, "7346f3a0ae6869da235dfcbad3af3b9cf284b0a1faf62d5055636cb775363eee");
        for (var operation : SiteMutation.Operation.values()) {
            var request = operation == SiteMutation.Operation.UPDATE ? patch : SiteInformationPatch.empty();
            assertEquals(hashes.get(operation), SiteMutationFingerprintV1.sign(command(operation, request), secret));
        }
    }

    @Test
    void excludesActorProcessAndKeyButIncludesCanonicalPresenceResourceVersionAndTenant() {
        var operation = SiteMutation.Operation.UPDATE;
        var patch = new SiteInformationPatch(FieldChange.present(" ß "), FieldChange.present(" compañía  ágil "), FieldChange.absent());
        var canonical = new SiteInformationPatch(FieldChange.present("SS"), FieldChange.present("COMPAÑÍA  ÁGIL"), FieldChange.absent());
        var hash = SiteMutationFingerprintV1.sign(command(operation, patch), secret);
        assertTrue(SiteMutationFingerprintV1.matches(command(operation, canonical), secret, hash));
        assertFalse(SiteMutationFingerprintV1.matches(command(UUID.randomUUID(), company, site, 7, operation, patch), secret, hash));
        assertFalse(SiteMutationFingerprintV1.matches(command(tenant, UUID.randomUUID(), site, 7, operation, patch), secret, hash));
        assertFalse(SiteMutationFingerprintV1.matches(command(tenant, company, UUID.randomUUID(), 7, operation, patch), secret, hash));
        assertFalse(SiteMutationFingerprintV1.matches(command(tenant, company, site, 8, operation, patch), secret, hash));
        var empty = SiteInformationPatch.empty();
        var noOpHash = SiteMutationFingerprintV1.sign(command(operation, empty), secret);
        assertFalse(SiteMutationFingerprintV1.matches(command(operation,
                new SiteInformationPatch(FieldChange.absent(), FieldChange.present("NAME"), FieldChange.absent())), secret, noOpHash));
        var activateHash = SiteMutationFingerprintV1.sign(command(SiteMutation.Operation.ACTIVATE, empty), secret);
        assertFalse(SiteMutationFingerprintV1.matches(command(SiteMutation.Operation.DEACTIVATE, empty), secret, activateHash));
    }

    @Test
    void retainsHistoricalKeyForEveryOperationAndRejectsUnavailableProvenance() {
        var old = Base64.getEncoder().encodeToString(secret);
        var next = Base64.getEncoder().encodeToString(new byte[32]);
        var k1 = new ConfiguredSiteMutationFingerprints(new HmacKeys("K1", Map.of("K1", old)));
        var k2 = new ConfiguredSiteMutationFingerprints(new HmacKeys("K2", Map.of("K1", old, "K2", next)));
        for (var operation : SiteMutation.Operation.values()) {
            var command = command(operation, SiteInformationPatch.empty());
            var signature = k1.sign(command);
            assertEquals("K2", k2.sign(command).keyId());
            assertTrue(k2.matches(command, signature.version(), signature.keyId(), signature.hash()));
            var hash = signature.hash();
            assertThrows(IllegalStateException.class, () -> k2.matches(command, (short) 2, "K1", hash));
            assertThrows(IllegalStateException.class, () -> k2.matches(command, (short) 1, "MISSING", hash));
        }
    }
}
