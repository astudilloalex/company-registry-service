package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class LifecycleResultCodecTest {
    private final LifecycleResultCodec codec = new LifecycleResultCodec();
    static CompanyRecord result() {
        var now = Instant.parse("2026-10-17T12:00:00.123456Z");
        return new CompanyRecord(new Company(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CompanyStatus.ACTIVE, new AggregateVersion(4)), "CODE", "COMPAÑÍA", null,
                "https://host/LogoAb.png", new Audit(now.minusSeconds(1), "Creator-aB", now, "Updater-aB"));
    }
    static CompletionRecord stored(CompanyRecord row, Map<String,Object> document) {
        return new CompletionRecord(new CompletionKey(row.company().tenantId(), "company.activate.v1", "Key-aB"),
                "a".repeat(64), row.company().id(), null, 3L, (short)200, (short)1, document,
                row.audit().updatedAt(), "Updater-aB", (short)1, "K1");
    }

    @Test
    void rejectsNoOpWrongDestinationAndAcceptanceAuditDisagreement() {
        var row = result();
        var stored = stored(row, codec.encode(row));
        for (var invalid : new CompletionRecord[] {
                new CompletionRecord(stored.key(), stored.requestHash(), stored.companyId(), null, 4L, (short) 200,
                        (short) 1, stored.snapshot(), stored.createdAt(), stored.createdBy(), (short) 1, "K1"),
                new CompletionRecord(new CompletionKey(stored.key().tenantId(), "company.deactivate.v1", stored.key().key()),
                        stored.requestHash(), stored.companyId(), null, 3L, (short) 200, (short) 1, stored.snapshot(),
                        stored.createdAt(), stored.createdBy(), (short) 1, "K1"),
                new CompletionRecord(stored.key(), stored.requestHash(), stored.companyId(), null, 3L, (short) 200,
                        (short) 1, stored.snapshot(), stored.createdAt().plusNanos(1000), stored.createdBy(), (short) 1, "K1"),
                new CompletionRecord(stored.key(), stored.requestHash(), stored.companyId(), null, 3L, (short) 200,
                        (short) 1, stored.snapshot(), stored.createdAt(), "Other actor", (short) 1, "K1")}) {
            var failure = assertThrows(IllegalStateException.class, () -> codec.decode(invalid));
            assertNull(failure.getCause());
        }
    }

    @Test
    void rejectsCoercionNoncanonicalUuidAndNonUtcDate() {
        var row = result();
        for (var value : new Object[] {"4", 4.0, true, null}) {
            var document = new LinkedHashMap<>(codec.encode(row));
            @SuppressWarnings("unchecked")
            var company = new LinkedHashMap<>((Map<String, Object>) document.get("company"));
            var version = new LinkedHashMap<String, Object>();
            version.put("value", value);
            company.put("version", version);
            document.put("company", company);
            var invalid = stored(row, document);
            assertThrows(IllegalStateException.class, () -> codec.decode(invalid));
        }
        var document = new LinkedHashMap<>(codec.encode(row));
        @SuppressWarnings("unchecked")
        var audit = new LinkedHashMap<>((Map<String, Object>) document.get("audit"));
        audit.put("updatedAt", "2026-10-17T12:00:00.123456+00:00");
        document.put("audit", audit);
        var invalidDate = stored(row, document);
        assertThrows(IllegalStateException.class, () -> codec.decode(invalidDate));
        @SuppressWarnings("unchecked")
        var company = new LinkedHashMap<>((Map<String, Object>) document.get("company"));
        company.put("id", "1-1-1-1-1");
        document.put("company", company);
        var invalidUuid = stored(row, document);
        assertThrows(IllegalStateException.class, () -> codec.decode(invalidUuid));
    }
    @Test void preservesNullsTypesAndOperationalAudit() {
        var row=result(); var document=codec.encode(row);
        assertTrue(document.containsKey("description")); assertNull(document.get("description"));
        assertFalse(document.containsKey("party"));
        assertEquals(row, codec.decode(stored(row,document)));
    }
    @Test void rejectsUnknownMissingAndMistypedFieldsWithoutLeakingValues() {
        var row = result();
        var encoded = codec.encode(row);
        for (var field : encoded.keySet()) {
            var document = new LinkedHashMap<>(encoded);
            document.remove(field);
            var missingField = stored(row, document);
            assertThrows(IllegalStateException.class, () -> codec.decode(missingField), field);
        }
        for (var field : new String[]{"party", "tenantId", "extra"}) {
            var document = new LinkedHashMap<>(encoded);
            document.put(field, "SECRET-VALUE");
            var unexpectedField = stored(row, document);
            var failure = assertThrows(IllegalStateException.class, () -> codec.decode(unexpectedField));
            assertFalse(failure.toString().contains("SECRET-VALUE"));
            assertNull(failure.getCause());
        }
        var document = new LinkedHashMap<>(encoded);
        document.put("code", 123);
        var mistypedField = stored(row, document);
        assertThrows(IllegalStateException.class, () -> codec.decode(mistypedField));
        var other = result();
        var mismatchedCompany = stored(other, encoded);
        assertThrows(IllegalStateException.class, () -> codec.decode(mismatchedCompany));
    }
    @Test void rejectsIncompatibleMetadataAndVersion() {
        var row=result();var original=stored(row,codec.encode(row));
        for(var expected: new Long[]{null,-1L,1L,Long.MAX_VALUE}) {
            var broken=new CompletionRecord(original.key(),original.requestHash(),original.companyId(),null,expected,
                    (short)200,(short)1,original.snapshot(),original.createdAt(),original.createdBy(),(short)1,"K1");
            assertThrows(IllegalStateException.class,()->codec.decode(broken));
        }
        var unsupported=new CompletionRecord(original.key(),original.requestHash(),original.companyId(),null,3L,
                (short)200,(short)2,original.snapshot(),original.createdAt(),original.createdBy(),(short)1,"K1");
        assertThrows(IllegalStateException.class,()->codec.decode(unsupported));
        var wrongStatus=new CompletionRecord(original.key(),original.requestHash(),original.companyId(),null,3L,
                (short)201,(short)1,original.snapshot(),original.createdAt(),original.createdBy(),(short)1,"K1");
        assertThrows(IllegalStateException.class,()->codec.decode(wrongStatus));
        var site=new CompletionRecord(original.key(),original.requestHash(),original.companyId(),UUID.randomUUID(),3L,
                (short)200,(short)1,original.snapshot(),original.createdAt(),original.createdBy(),null,null);
        assertThrows(IllegalStateException.class,()->codec.decode(site));
    }
    @Test void handlesBigIntegerVersionCorrectly() {
        var row = result();
        var document = new LinkedHashMap<>(codec.encode(row));
        @SuppressWarnings("unchecked")
        var company = new LinkedHashMap<>((Map<String, Object>) document.get("company"));
        document.put("company", company);

        company.put("version", Map.of("value", java.math.BigInteger.valueOf(4)));
        assertEquals(row, codec.decode(stored(row, document)));

        company.put("version", Map.of("value", java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE)));
        var overflowing = stored(row, document);
        assertThrows(IllegalStateException.class, () -> codec.decode(overflowing));

        company.put("version", Map.of("value", java.math.BigInteger.valueOf(-1)));
        var negative = stored(row, document);
        assertThrows(IllegalStateException.class, () -> codec.decode(negative));
    }
}
