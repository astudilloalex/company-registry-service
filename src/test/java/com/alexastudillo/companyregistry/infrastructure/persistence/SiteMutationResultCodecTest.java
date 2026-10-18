package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SiteMutationResultCodecTest {
    private final SiteMutationResultCodec codec = new SiteMutationResultCodec();
    static SiteMutation.Result result(UUID tenant, UUID company) {
        var now = Instant.parse("2026-10-17T12:00:00.123456Z");
        return new SiteMutation.Result(new SiteRecord(new Site(new UuidV7Generator().next(), tenant, company,
                SiteType.BRANCH, SiteStatus.ACTIVE, new AggregateVersion(8)), "SEDE", "COMPAÑÍA  ÁGIL",
                new Audit(now.minusSeconds(60), "Creator", now, " Actor-aB ")), CompanyStatus.ACTIVE);
    }
    static CompletionRecord stored(SiteMutation.Result result, Map<String, Object> snapshot) {
        var row = result.site();
        return new CompletionRecord(new CompletionKey(row.site().tenantId(), SiteMutation.Operation.ACTIVATE.namespace(), UUID.randomUUID().toString()),
                "a".repeat(64), row.site().companyId(), row.site().id(), 7L, (short)200, (short)1, snapshot,
                row.audit().updatedAt(), row.audit().updatedBy(), (short)1, "K1");
    }
    @Test void roundTripsAllOriginalValues() {
        var result = result(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(result, codec.decode(stored(result, codec.encode(result))));
    }
    @Test void rejectsMissingExtraMistypedAndChangedValuesWithoutExposingTheirContent() {
        var result = result(UUID.randomUUID(), UUID.randomUUID());
        var document = codec.encode(result);
        for (var path : new String[][] {{"companyStatus"}, {"effectivelyOperational"}, {"site", "name"}, {"site", "code"},
                {"site", "site", "id"}, {"site", "site", "companyId"}, {"site", "site", "tenantId"},
                {"site", "site", "type"}, {"site", "site", "status"}, {"site", "site", "version", "value"},
                {"site", "audit", "createdAt"}, {"site", "audit", "updatedAt"},
                {"site", "audit", "createdBy"}, {"site", "audit", "updatedBy"}}) {
            for (var wrong : new Object[] {"", 2.5, Map.of(), List.of()}) {
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
        var active = copy(document); cast(cast(active.get("site")).get("site")).put("status", "INACTIVE");
        var activeStored = stored(result, active);
        assertThrows(IllegalStateException.class, () -> codec.decode(activeStored));
        var operational = copy(document); operational.put("effectivelyOperational", false);
        var operationalStored = stored(result, operational);
        assertThrows(IllegalStateException.class, () -> codec.decode(operationalStored));
    }
    @Test void roundTripsEveryOperationAndNoOpWithHistoricalAudit() {
        var original = result(UUID.randomUUID(), UUID.randomUUID());
        for (var operation : SiteMutation.Operation.values()) {
            for (var parent : new CompanyStatus[] {CompanyStatus.DRAFT, CompanyStatus.ACTIVE, CompanyStatus.INACTIVE}) {
                var status = operation == SiteMutation.Operation.UPDATE ? SiteStatus.DRAFT : operation.target();
                var row = original.site();
                var site = row.site();
                var accepted = new SiteMutation.Result(new SiteRecord(new Site(site.id(), site.tenantId(), site.companyId(),
                        site.type(), status, new AggregateVersion(8)), row.code(), row.name(), row.audit()), parent);
                var stored = completion(accepted, operation, 7, accepted.site().audit().updatedAt(), accepted.site().audit().updatedBy());
                assertEquals(accepted, codec.decode(stored));
                assertEquals(parent == CompanyStatus.ACTIVE && status == SiteStatus.ACTIVE,
                        codec.decode(stored).effectivelyOperational());
                if (operation == SiteMutation.Operation.UPDATE) {
                    var noop = completion(accepted, operation, 8, row.audit().updatedAt().plusSeconds(60), "OtherActor");
                    assertEquals(accepted, codec.decode(noop));
                }
            }
        }
    }

    @Test void rejectsWrongRevisionAndLifecycleStateWithoutRepair() {
        var result = result(UUID.randomUUID(), UUID.randomUUID());
        for (var operation : SiteMutation.Operation.values()) {
            for (long version : new long[] {0, 8, Long.MAX_VALUE}) {
                if (operation == SiteMutation.Operation.UPDATE && version == 8) continue;
                var stored = completion(result, operation, version, result.site().audit().updatedAt(), result.site().audit().updatedBy());
                assertThrows(IllegalStateException.class, () -> codec.decode(stored));
            }
        }
    }

    @Test void rejectsUnsupportedSnapshotFormat() {
        var result = result(UUID.randomUUID(), UUID.randomUUID());
        var original = stored(result, codec.encode(result));
        var unknown = new CompletionRecord(original.key(), original.requestHash(), original.companyId(), original.siteId(),
                original.expectedVersion(), original.resultStatus(), (short)2, original.snapshot(), original.createdAt(),
                original.createdBy(), original.fingerprintVersion(), original.fingerprintKeyId());
        var failure = assertThrows(IllegalStateException.class, () -> codec.decode(unknown));
        assertNull(failure.getCause());
    }

    private CompletionRecord completion(SiteMutation.Result result, SiteMutation.Operation operation,
            long expected, Instant acceptedAt, String actor) {
        var base = stored(result, codec.encode(result));
        return new CompletionRecord(new CompletionKey(base.key().tenantId(), operation.namespace(), base.key().key()),
                base.requestHash(), base.companyId(), base.siteId(), expected, (short)200, (short)1,
                base.snapshot(), acceptedAt, actor, (short)1, "K1");
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        var copy = new LinkedHashMap<String, Object>();
        value.forEach((key, item) -> copy.put(key, item instanceof Map<?, ?> ? copy(cast(item)) : item));
        return copy;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
}
