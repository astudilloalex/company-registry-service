package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.api.company.CreateCompanyRequest;
import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreationFingerprintV1Test {
    private final UUID tenant = UUID.fromString("018f1234-5678-7000-8000-000000000001");
    private final UUID party = UUID.fromString("018f1234-5678-7000-8000-000000000004");
    private final byte[] secret = new byte[32];
    CreationFingerprintV1Test() { for (int i = 0; i < secret.length; i++) secret[i] = (byte) i; }
    private CompanyRegistration.Command command(String code, String name, String description, String url) {
        return new CompanyRegistration.Command(new OperationContext(tenant, "Actor-aB", UUID.randomUUID().toString()), "Key-aB", party, code, name, description, url);
    }

    @Test void matchesIndependentUtf8ByteAndHmacVector() {
        var command = command(" ß ", " compañía  ágil ", null, "https://example.com/Logo.png?Token=aB");
        var expected = "{\"fingerprintVersion\":1,\"tenantId\":\"018f1234-5678-7000-8000-000000000001\",\"operation\":\"company.create.v1\",\"request\":{\"partyId\":\"018f1234-5678-7000-8000-000000000004\",\"code\":\"SS\",\"operatingName\":\"COMPAÑÍA  ÁGIL\",\"description\":null,\"logoUrl\":\"https://example.com/Logo.png?Token=aB\"}}";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), CreationFingerprintV1.bytes(command));
        assertEquals("3cc2e8067e91e6f1e2ed22846c19edc8ab113ac5c4f0c1b98bf2a2b4c3b595a8", CreationFingerprintV1.sign(command, secret));
    }

    @Test void presentationOrderNullOmissionAndContextDoNotChangeEffectiveContent() throws Exception {
        var json = new ObjectMapper();
        var context = new OperationContext(tenant, "Another-actor", UUID.randomUUID().toString());
        var a = json.readValue("{\"partyId\":\"" + party + "\",\"code\":\"acme\",\"operatingName\":\"ágil\"}", CreateCompanyRequest.class);
        var b = json.readValue("{ \"logoUrl\":null, \"operatingName\":\" ÁGIL \", \"description\":null, \"code\":\" ACME \", \"partyId\":\"" + party + "\" }", CreateCompanyRequest.class);
        var hash = CreationFingerprintV1.sign(a.command(context, "another-key"), secret);
        assertTrue(CreationFingerprintV1.matches(b.command(context, "key"), secret, hash));
        assertTrue(CreationFingerprintV1.matches(command("acme", "ágil", null, null), secret, hash));
        assertFalse(CreationFingerprintV1.matches(command("acme", "á gil", null, null), secret, hash));
        var url = command("acme", "ágil", null, "https://example.com/Logo.png");
        assertFalse(CreationFingerprintV1.matches(command("acme", "ágil", null, "https://example.com/logo.png"), secret, CreationFingerprintV1.sign(url, secret)));
    }

    @Test void escapingIsDeterministicAndUnambiguous() {
        var command = command("acme", "name \"quoted\" \\ line\nnext", null, null);
        var bytes = new String(CreationFingerprintV1.bytes(command), StandardCharsets.UTF_8);
        assertTrue(bytes.contains("\\\"QUOTED\\\" \\\\ LINE\\nNEXT"));
        assertEquals(CreationFingerprintV1.sign(command, secret), CreationFingerprintV1.sign(command, secret));
    }
}
