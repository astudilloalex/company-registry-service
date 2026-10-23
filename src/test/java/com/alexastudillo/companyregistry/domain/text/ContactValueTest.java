package com.alexastudillo.companyregistry.domain.text;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.domain.model.ContactType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactValueTest {
    @Test
    void lowercasesEmailAndTrimsOnlyExteriorWhitespace() {
        assertEquals("mi.usuario@example.com", ContactValue.normalize(ContactType.EMAIL, " Mi.Usuario@Example.com "));
        assertEquals("user@sub.domain.example.com", ContactValue.normalize(ContactType.EMAIL, "user@sub.domain.example.com"));
        for (String invalid : new String[] {"a", "a@@example.com", "a b@example.com", "a@b", "", "a\n@example.com",
                "user@.com", "user@example."}) {
            assertThrows(InvalidText.class, () -> ContactValue.normalize(ContactType.EMAIL, invalid));
        }
    }

    @Test
    void preservesAliasesAndRejectsNonAsciiAndInvalidComponents() {
        assertEquals("a.b+alias@example.com", ContactValue.normalize(ContactType.EMAIL, " A.B+Alias@Example.COM "));
        for (String invalid : new String[] {"josé@example.com", "K@example.com", "a..b@example.com",
                ".a@example.com", "a.@example.com", "a@example..com", "a@-example.com", "a@example-.com",
                "a@localhost", "a@[127.0.0.1]", "\"a\"@example.com", "A <a@example.com>",
                "a(comment)@example.com", "a@example.com,b@example.com", "a@" + "b".repeat(64) + ".com"}) {
            assertThrows(InvalidText.class, () -> ContactValue.normalize(ContactType.EMAIL, invalid), invalid);
        }
    }

    @Test
    void enforcesCanonicalMailboxAndLocalLengthBoundaries() {
        String local64 = "a".repeat(64);
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        String maximum = local64 + "@" + domain;
        assertEquals(254, maximum.length());
        assertEquals(maximum, ContactValue.normalize(ContactType.EMAIL, " " + maximum + " "));
        String overTotal = maximum + "d";
        String overLocal = local64 + "a@example.com";
        var totalFailure = assertThrows(InvalidText.class, () -> ContactValue.normalize(ContactType.EMAIL, overTotal));
        var localFailure = assertThrows(InvalidText.class, () -> ContactValue.normalize(ContactType.EMAIL, overLocal));
        assertEquals("contact-value-too-long", totalFailure.getMessage());
        assertEquals("contact-value-too-long", localFailure.getMessage());
    }

    @Test
    void requiresMobileClassificationOnlyForMobileChannel() {
        String fixed = "+442079460018";
        assertEquals(fixed, ContactValue.normalize(ContactType.PHONE, fixed));
        assertEquals(fixed, ContactValue.normalize(ContactType.WHATSAPP, fixed));
        var failure = assertThrows(InvalidText.class, () -> ContactValue.normalize(ContactType.MOBILE, fixed));
        assertEquals("contact-mobile-number-required", failure.getMessage());
        assertEquals("+447911123456", ContactValue.normalize(ContactType.MOBILE, "+44 7911 123456"));
    }

    @Test
    void normalizesTelephoneTypesToE164WithoutInventingACountry() {
        for (var type : new ContactType[] {ContactType.PHONE, ContactType.MOBILE, ContactType.WHATSAPP}) {
            assertEquals("+12025550123", ContactValue.normalize(type, " +1 (202) 555-0123 "));
            assertEquals("+12025550123", ContactValue.normalize(type, "+12025550123"));
            for (var invalid : new String[] {"2025550123", "0012025550123", "+9991234567", "+1", "+0123",
                    "+12025550123 ext 5", "+1234567890123456", "+1CALLNOW", ""}) {
                assertThrows(InvalidText.class, () -> ContactValue.normalize(type, invalid), invalid);
            }
            assertThrows(InvalidText.class, () -> ContactValue.normalize(type, null));
        }
    }

    @Test
    void preservesOpaqueContextCase() {
        var tenant = UUID.fromString("018e2b3c-1234-7123-8123-abcdef012345");
        var process = "018E2B3C-1234-7123-8123-ABCDEF012345";
        var context = new OperationContext(tenant, "Mi.Usuario", process);
        assertEquals(tenant, context.tenantId());
        assertEquals(process, context.processId());
        assertEquals("Mi.Usuario", context.actor());
    }

    @Test
    void opaqueValuesNeverPassThroughUppercaseOrTrimming() {
        for (var value : new String[] {"https://host/LogoAb.png", "Key-aB", " Key-aB ",
                "018E2B3C-1234-7123-8123-ABCDEF012345", "hmac-aB12+/=", "correlation-Ab"}) {
            assertEquals(value, OpaqueValue.required(value, 1000));
        }
        assertThrows(InvalidText.class, () -> OpaqueValue.required(" ", 128));
        assertThrows(InvalidText.class, () -> OpaqueValue.required("long", 3));
    }
}
