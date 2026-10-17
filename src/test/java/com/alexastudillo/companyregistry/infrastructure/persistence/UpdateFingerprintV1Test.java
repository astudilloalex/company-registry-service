package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.company.CompanyUpdate;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.domain.model.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class UpdateFingerprintV1Test {
    private static final UUID TENANT = UUID.fromString("0199e000-0000-7000-8000-000000000001");
    private static final UUID COMPANY = UUID.fromString("0199e000-0000-7000-8000-000000000002");
    private static final byte[] SECRET = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
    private CompanyUpdate.Command command(CompanyInformationPatch patch) {
        return new CompanyUpdate.Command(new OperationContext(TENANT, "Actor-aB", UUID.randomUUID().toString()), COMPANY, 3, "Key-aB", patch);
    }

    @Test
    void fixedCanonicalBytesAndIndependentHmac() throws Exception {
        var cmd = command(new CompanyInformationPatch(FieldChange.present(" ß "), FieldChange.absent(),
                FieldChange.present(null), FieldChange.present("https://host/LogoAb.png")));
        var expected = "{\"fingerprintVersion\":1,\"tenantId\":\"" + TENANT
                + "\",\"operation\":\"company.update.v1\",\"companyId\":\"" + COMPANY
                + "\",\"expectedVersion\":\"3\",\"patch\":{\"code\":{\"present\":true,\"value\":\"SS\"},"
                + "\"operatingName\":{\"present\":false},\"description\":{\"present\":true,\"value\":null},"
                + "\"logoUrl\":{\"present\":true,\"value\":\"https://host/LogoAb.png\"}}}";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), UpdateFingerprintV1.bytes(cmd));
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(expected.getBytes(StandardCharsets.UTF_8))), UpdateFingerprintV1.sign(cmd, SECRET));
        assertTrue(UpdateFingerprintV1.matches(cmd, SECRET, UpdateFingerprintV1.sign(cmd, SECRET)));
    }

    @Test
    void canonicalTextIsEquivalentRegardlessOfContextOrKey() {
        var raw = command(new CompanyInformationPatch(FieldChange.present(" code "), FieldChange.present(" compañía  ágil "),
                FieldChange.absent(), FieldChange.absent()));
        var normalized = new CompanyUpdate.Command(new OperationContext(TENANT, "Other actor", UUID.randomUUID().toString()), COMPANY,
                3, null, new CompanyInformationPatch(FieldChange.present("CODE"), FieldChange.present("COMPAÑÍA  ÁGIL"),
                        FieldChange.absent(), FieldChange.absent()));
        assertArrayEquals(UpdateFingerprintV1.bytes(raw), UpdateFingerprintV1.bytes(normalized));
    }

    @Test
    void resourceVersionTenantPresenceValueAndExactUrlAreIntent() {
        var empty = command(CompanyInformationPatch.empty());
        var hash = UpdateFingerprintV1.sign(empty, SECRET);
        assertFalse(UpdateFingerprintV1.matches(new CompanyUpdate.Command(empty.context(), UUID.randomUUID(), 3, "k", empty.patch()), SECRET, hash));
        assertFalse(UpdateFingerprintV1.matches(new CompanyUpdate.Command(empty.context(), COMPANY, 4, "k", empty.patch()), SECRET, hash));
        assertFalse(UpdateFingerprintV1.matches(new CompanyUpdate.Command(new OperationContext(UUID.randomUUID(), "actor", UUID.randomUUID().toString()),
                COMPANY, 3, "k", empty.patch()), SECRET, hash));
        for (var field : new FieldChange[] {FieldChange.present(null), FieldChange.present("NEW")}) {
            assertFalse(UpdateFingerprintV1.matches(command(new CompanyInformationPatch(FieldChange.absent(), FieldChange.absent(), field,
                    FieldChange.absent())), SECRET, hash));
        }
        var logo = command(new CompanyInformationPatch(FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.present("https://host/Logo.png")));
        var lower = command(new CompanyInformationPatch(FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.present("https://host/logo.png")));
        assertFalse(UpdateFingerprintV1.matches(lower, SECRET, UpdateFingerprintV1.sign(logo, SECRET)));
        assertThrows(IllegalStateException.class, () -> UpdateFingerprintV1.matches(empty, SECRET, "bad"));
    }
    @Test
    void rotationSelectsStoredKeyAndRejectsUnknownHistoricalMaterial() {
        var encoded = java.util.Base64.getEncoder();
        var keys = java.util.Map.of("K1", encoded.encodeToString(SECRET), "K2", encoded.encodeToString("98765432109876543210987654321098".getBytes(StandardCharsets.UTF_8)));
        var old = new ConfiguredUpdateFingerprints(new HmacKeys("K1", keys));
        var rotated = new ConfiguredUpdateFingerprints(new HmacKeys("K2", keys));
        var cmd = command(CompanyInformationPatch.empty());
        var signature = old.sign(cmd);
        var hash = signature.hash();
        assertNotEquals(hash, rotated.sign(cmd).hash());
        assertTrue(rotated.matches(cmd, signature.version(), signature.keyId(), hash));
        assertThrows(IllegalStateException.class, () -> rotated.matches(cmd, (short) 2, "K1", hash));
        assertThrows(IllegalStateException.class, () -> rotated.matches(cmd, (short) 1, "missing", hash));
    }

}
