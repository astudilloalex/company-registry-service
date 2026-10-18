package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.site.SiteRegistration;
import com.alexastudillo.companyregistry.domain.model.FieldChange;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SiteFingerprintTest {
    private final UUID tenant = UUID.fromString("018f1234-5678-7000-8000-000000000001");
    private final UUID company = UUID.fromString("018f1234-5678-7000-8000-000000000004");
    private final byte[] secret = new byte[32];
    SiteFingerprintTest() { for (int i = 0; i < secret.length; i++) secret[i] = (byte) i; }
    private SiteRegistration.Command command(UUID companyId, String code, String name, FieldChange type) {
        return new SiteRegistration.Command(new OperationContext(tenant, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                UUID.randomUUID().toString(), companyId, code, name, type);
    }

    @Test void matchesIndependentCanonicalUtf8AndHmacVector() {
        var command = command(company, " ß ", " compañía  ágil ", FieldChange.absent());
        var expected = "{\"fingerprintVersion\":1,\"tenantId\":\"018f1234-5678-7000-8000-000000000001\",\"operation\":\"company.site.create.v1\",\"companyId\":\"018f1234-5678-7000-8000-000000000004\",\"code\":\"SS\",\"name\":\"COMPAÑÍA  ÁGIL\",\"siteType\":\"BRANCH\"}";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), SiteCreationFingerprintV1.bytes(command));
        assertEquals("c99492a23b6b1709c172b8f684518524b79ae5dd6cff22598b77157f0cd4fb0f", SiteCreationFingerprintV1.sign(command, secret));
    }

    @Test void effectiveEquivalenceExcludesActorProcessAndKeyButIncludesCompanyAndInnerSpaces() {
        var hash = SiteCreationFingerprintV1.sign(command(company, " ß ", " compañía  ágil ", FieldChange.absent()), secret);
        assertTrue(SiteCreationFingerprintV1.matches(command(company, "SS", "COMPAÑÍA  ÁGIL", FieldChange.present("BRANCH")), secret, hash));
        assertFalse(SiteCreationFingerprintV1.matches(command(UUID.randomUUID(), "SS", "COMPAÑÍA  ÁGIL", FieldChange.absent()), secret, hash));
        assertFalse(SiteCreationFingerprintV1.matches(command(company, "SS", "COMPAÑÍA ÁGIL", FieldChange.absent()), secret, hash));
        assertFalse(SiteCreationFingerprintV1.matches(command(company, "SS", "COMPAÑÍA  ÁGIL", FieldChange.present("OTHER")), secret, hash));
    }

    @Test void retainsHistoricalKeyAndPolicyAfterRotationAndSanitizesUnavailableProvenance() {
        var old = Base64.getEncoder().encodeToString(secret);
        var next = Base64.getEncoder().encodeToString(new byte[32]);
        var command = command(company, "x", "y", FieldChange.absent());
        var k1 = new ConfiguredSiteFingerprints(new HmacKeys("K1", Map.of("K1", old)));
        var signature = k1.sign(command);
        var k2 = new ConfiguredSiteFingerprints(new HmacKeys("K2", Map.of("K1", old, "K2", next)));
        assertEquals("K2", k2.sign(command).keyId());
        var hash = signature.hash();
        assertTrue(k2.matches(command, signature.version(), signature.keyId(), hash));
        assertThrows(IllegalStateException.class, () -> k2.matches(command, (short) 2, "K1", hash));
        assertThrows(IllegalStateException.class, () -> k2.matches(command, (short) 1, "MISSING", hash));
    }
}
