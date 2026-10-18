package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SiteResultCodecTest {
    private final SiteResultCodec codec = new SiteResultCodec();
    static SiteRegistration.Result result(UUID tenant, UUID company) {
        var now = Instant.parse("2026-10-17T12:00:00.123456Z");
        return new SiteRegistration.Result(new SiteRecord(new Site(new UuidV7Generator().next(), tenant, company,
                SiteType.BRANCH, SiteStatus.DRAFT, AggregateVersion.initial()), "SEDE", "COMPAÑÍA  ÁGIL",
                new Audit(now, " Actor-aB ", now, " Actor-aB ")), false);
    }
    static CompletionRecord stored(SiteRegistration.Result result, Map<String, Object> snapshot) {
        var row = result.site();
        return new CompletionRecord(new CompletionKey(row.site().tenantId(), SiteRegistration.OPERATION, UUID.randomUUID().toString()),
                "a".repeat(64), row.site().companyId(), row.site().id(), null, (short)201, (short)1, snapshot,
                row.audit().createdAt(), row.audit().createdBy(), (short)1, "K1");
    }
    @Test void roundTripsAllOriginalValues() {
        var result = result(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(result, codec.decode(stored(result, codec.encode(result))));
    }
    @Test void rejectsMissingExtraMistypedAndChangedValuesWithoutExposingTheirContent() {
        var result = result(UUID.randomUUID(), UUID.randomUUID());
        var document = codec.encode(result);
        for (var path : new String[][] {{"effectivelyOperational"}, {"site", "name"}, {"site", "code"},
                {"site", "site", "id"}, {"site", "site", "companyId"}, {"site", "site", "tenantId"},
                {"site", "site", "type"}, {"site", "site", "status"}, {"site", "site", "version", "value"},
                {"site", "audit", "createdAt"}, {"site", "audit", "updatedAt"},
                {"site", "audit", "createdBy"}, {"site", "audit", "updatedBy"}}) {
            for (var wrong : new Object[] {"secret-value", 2.5, Map.of(), List.of()}) {
                var changed = copy(document);
                Map<String, Object> parent = changed;
                for (int i = 0; i < path.length - 1; i++) parent = cast(parent.get(path[i]));
                parent.put(path[path.length - 1], wrong);
                var wrongStored = stored(result, changed);
                var failure = assertThrows(IllegalStateException.class, () -> codec.decode(wrongStored));
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains("secret-value"));
            }
            var changed = copy(document);
            Map<String, Object> parent = changed;
            for (int i = 0; i < path.length - 1; i++) parent = cast(parent.get(path[i]));
            parent.remove(path[path.length - 1]);
            var missingStored = stored(result, changed);
            assertThrows(IllegalStateException.class, () -> codec.decode(missingStored));
        }
        var extraDocument = copy(document); extraDocument.put("extra", "secret-value");
        var extraStored = stored(result, extraDocument);
        assertThrows(IllegalStateException.class, () -> codec.decode(extraStored));
        var other = result(UUID.randomUUID(), UUID.randomUUID());
        var mismatchedStored = stored(other, document);
        assertThrows(IllegalStateException.class, () -> codec.decode(mismatchedStored));
        var active = copy(document); cast(cast(active.get("site")).get("site")).put("status", "ACTIVE");
        var activeStored = stored(result, active);
        assertThrows(IllegalStateException.class, () -> codec.decode(activeStored));
        var operational = copy(document); operational.put("effectivelyOperational", true);
        var operationalStored = stored(result, operational);
        assertThrows(IllegalStateException.class, () -> codec.decode(operationalStored));
    }
    private static Map<String, Object> copy(Map<String, Object> value) {
        var copy = new LinkedHashMap<String, Object>();
        value.forEach((key, item) -> copy.put(key, item instanceof Map<?, ?> ? copy(cast(item)) : item));
        return copy;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
}
