package com.alexastudillo.companyregistry.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexastudillo.companyregistry.application.site.*;
import com.alexastudillo.companyregistry.domain.model.ContactType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactCursorTest {
    private final ContactScope scope = new ContactScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final ContactSelection.Filters filters = new ContactSelection.Filters(ContactType.EMAIL, true);
    private final ContactSelection.Boundary boundary = new ContactSelection.Boundary(Instant.parse("2026-10-18T20:00:00.123456Z"), UUID.randomUUID());
    private final Map<String, String> keys = Map.of("K1", Base64.getEncoder().encodeToString(new byte[32]),
            "K2", Base64.getEncoder().encodeToString("another-contact-cursor-key-material".getBytes(StandardCharsets.UTF_8)));

    @Test
    void retainsBoundaryAcrossRotationAndIndependentInstancesWithDifferentPageSizes() {
        var original = new AuthenticatedContactCursors(new HmacKeys("K1", keys));
        var rotated = new AuthenticatedContactCursors(new HmacKeys("K2", keys));
        var cursor = original.encode(scope, filters, boundary);
        assertEquals(boundary, rotated.decode(cursor, scope, filters));
        for (int limit : new int[] {1, 50, 200}) {
            assertEquals(boundary, rotated.decode(new ContactSelection.Request(filters, limit, cursor).cursor(), scope, filters));
        }
        var unfiltered = new ContactSelection.Filters(null, null);
        assertEquals(boundary, rotated.decode(original.encode(scope, unfiltered, boundary), scope, unfiltered));
    }

    @Test
    void rejectsEveryScopeFilterMutationAndOtherCollection() {
        var codec = new AuthenticatedContactCursors(new HmacKeys("K1", keys));
        var cursor = codec.encode(scope, filters, boundary);
        for (var other : new ContactScope[] {new ContactScope(UUID.randomUUID(), scope.companyId(), scope.siteId()),
                new ContactScope(scope.tenantId(), UUID.randomUUID(), scope.siteId()),
                new ContactScope(scope.tenantId(), scope.companyId(), UUID.randomUUID())}) {
            invalid(() -> codec.decode(cursor, other, filters));
        }
        invalid(() -> codec.decode(cursor, scope, new ContactSelection.Filters(ContactType.PHONE, true)));
        invalid(() -> codec.decode(cursor, scope, new ContactSelection.Filters(ContactType.EMAIL, false)));
        invalid(() -> codec.decode(cursor, scope, new ContactSelection.Filters(ContactType.EMAIL, null)));
        var siteCursor = new AuthenticatedSiteCursors(new HmacKeys("K1", keys)).encode(scope.tenantId(), scope.companyId(),
                new SiteSelection.Filters(null, null, null), new SiteSelection.Boundary(boundary.createdAt(), boundary.contactId()));
        invalid(() -> codec.decode(siteCursor, scope, filters));
    }

    @Test
    void rejectsTamperingUnknownVersionsKeysAndMalformedTokensWithoutCauses() {
        var codec = new AuthenticatedContactCursors(new HmacKeys("K1", keys));
        var cursor = codec.encode(scope, filters, boundary);
        for (var value : new String[] {"", " ", "invalid", cursor + ".extra", "x".repeat(4097)}) {
            invalid(() -> codec.decode(value, scope, filters));
        }
        var parts = cursor.split("\\.");
        invalid(() -> codec.decode(parts[0] + ".AAAA", scope, filters));
        var body = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).replace("\"version\":1", "\"version\":2");
        var changed = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "." + parts[1];
        invalid(() -> codec.decode(changed, scope, filters));
        var removed = new AuthenticatedContactCursors(new HmacKeys("K2", Map.of("K2", keys.get("K2"))));
        invalid(() -> removed.decode(cursor, scope, filters));
    }

    private static void invalid(org.junit.jupiter.api.function.Executable work) {
        var failure = assertThrows(ContactFailure.class, work);
        assertEquals(ContactFailure.Kind.CURSOR_INVALID, failure.kind());
        assertNull(failure.getCause());
    }
}
