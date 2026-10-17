package com.alexastudillo.companyregistry.domain.text;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.domain.model.ContactType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactValueTest {
    @Test
    void preservesEmailCaseAndTrimsOnlyExteriorWhitespace() {
        assertEquals("Mi.Usuario@Example.com", ContactValue.normalize(ContactType.EMAIL, " Mi.Usuario@Example.com "));
        assertEquals("user@sub.domain.example.com", ContactValue.normalize(ContactType.EMAIL, "user@sub.domain.example.com"));
        for (String invalid : new String[] {"a", "a@@example.com", "a b@example.com", "a@b", "", "a\n@example.com",
                "user@.com", "user@example."}) {
            assertThrows(InvalidText.class, () -> ContactValue.normalize(ContactType.EMAIL, invalid));
        }
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
