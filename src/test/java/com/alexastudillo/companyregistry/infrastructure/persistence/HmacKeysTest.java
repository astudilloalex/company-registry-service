package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class HmacKeysTest {
    private static final String K1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String K2 = Base64.getEncoder().encodeToString("independent-test-key-material-0002".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private final CompanyRegistration.Command command = new CompanyRegistration.Command(new OperationContext(UUID.randomUUID(), "actor", UUID.randomUUID().toString()), "key", UUID.randomUUID(), "a", "a", null, null);

    @Test void anotherInstanceVerifiesHistoricalKeyAfterRotation() {
        var first = new ConfiguredFingerprints(new HmacKeys("K1", Map.of("K1", K1)));
        var rotated = new ConfiguredFingerprints(new HmacKeys("K2", Map.of("K1", K1, "K2", K2)));
        var old = first.sign(command); var current = rotated.sign(command);
        assertEquals("K2", current.keyId()); assertNotEquals(old.hash(), current.hash());
        assertTrue(rotated.matches(command, old.version(), old.keyId(), old.hash()));
        var missing = new ConfiguredFingerprints(new HmacKeys("K2", Map.of("K2", K2)));
        var oldVersion = old.version();
        var oldKeyId = old.keyId();
        var oldHash = old.hash();
        assertThrows(IllegalStateException.class, () -> missing.matches(command, oldVersion, oldKeyId, oldHash));
        assertThrows(IllegalStateException.class, () -> rotated.matches(command, (short) 2, oldKeyId, oldHash));
    }

    @Test void invalidConfigurationHasNoSecretInDiagnostics() {
        var validKeyring = Map.of("K1", K1);
        for (var invalid : List.of("", " ", "a".repeat(65), "key.with.dot")) {
            assertThrows(IllegalArgumentException.class, () -> new HmacKeys(invalid, validKeyring));
        }
        for (var invalid : List.of("secret-that-must-not-be-logged", Base64.getEncoder().encodeToString(new byte[31]))) {
            var invalidKeyring = Map.of("K1", invalid);
            var failure = assertThrows(IllegalArgumentException.class, () -> new HmacKeys("K1", invalidKeyring));
            assertEquals("idempotency-keyring-invalid", failure.getMessage()); assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> new HmacKeys("K2", validKeyring));
        var keys = new HmacKeys("K1", validKeyring); var copy = keys.secret("K1"); copy[0] = 1;
        assertEquals(0, keys.secret("K1")[0]);
    }

    @Test void macStateIsIsolatedAcrossConcurrentRequests() throws Exception {
        var fingerprints = new ConfiguredFingerprints(new HmacKeys("K1", Map.of("K1", K1)));
        var hash = fingerprints.sign(command).hash();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<String>>();
            for (int i = 0; i < 100; i++) calls.add(executor.submit(() -> fingerprints.sign(command).hash()));
            for (var call : calls) assertEquals(hash, call.get(5, TimeUnit.SECONDS));
        }
    }
}
