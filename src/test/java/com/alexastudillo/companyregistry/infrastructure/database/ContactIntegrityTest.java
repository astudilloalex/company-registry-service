package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class ContactIntegrityTest extends SqlIntegritySupport {
    private static final String TABLE = "company_site_contact_points";
    private static final String PREFIX = "ck_company_site_contacts_";

    private String insert(String type, String value, boolean primary) {
        return "INSERT INTO " + TABLE + "(site_id,contact_type,value,is_primary,created_by,updated_by) "
                + "VALUES('" + site + "','" + type + "','" + value + "'," + primary + ",'actor','actor')";
    }

    @Test
    void keysOwnerTypesAndEmailPreservation() throws Exception {
        var contact = uuid(insert("EMAIL", "Mi.Usuario@example.com", false) + " RETURNING id");
        assertEquals(7, contact.version());
        assertEquals("Mi.Usuario@example.com", scalar("SELECT value FROM " + TABLE + " WHERE id='" + contact + "'"));
        rejects("23505", "pk_company_site_contact_points", "INSERT INTO " + TABLE + " SELECT * FROM " + TABLE + " WHERE id='" + contact + "'");
        rejects("23503", "fk_company_site_contacts_site", insert("EMAIL", "Other@example.com", false).replace(site.toString(), UUID.randomUUID().toString()));
        rejects("23001", "fk_company_site_contacts_site", "DELETE FROM company_sites WHERE id='" + site + "'");
        assertEquals("EMAIL,PHONE,MOBILE,WHATSAPP", scalar("SELECT string_agg(enumlabel,',' ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid='company_site_contact_type'::regtype"));
        rejects("22P02", null, insert("UNKNOWN", "value", false));
    }

    @Test
    void everyValueLabelAndAuditCheck() throws Exception {
        var contact = uuid(insert("EMAIL", "Mi.Usuario@example.com", false) + " RETURNING id");
        for (var value : new String[]{"''", "'  '", "' Other@example.com'", "'Other@example.com '"}) {
            check(TABLE, contact, PREFIX + "value", "value=" + value);
        }
        canonical(TABLE, contact, PREFIX + "label", "label", true);
        rejects("23514", PREFIX + "created_by", insert("EMAIL", "Other@example.com", false).replace("'actor','actor'", "'  ','actor'"));
        check(TABLE, contact, PREFIX + "updated_by", "updated_by='  '");
        check(TABLE, contact, PREFIX + "audit_dates", "updated_at=created_at-interval '1 second'");
        execute("UPDATE " + TABLE + " SET updated_by='Actor-aB',updated_at=created_at+interval '1 second' WHERE id='" + contact + "'");
        assertEquals("Actor-aB", scalar("SELECT updated_by FROM " + TABLE + " WHERE id='" + contact + "'"));
    }

    @Test
    void allTelephoneTypesRequireE164ButEmailIsNotUppercased() throws Exception {
        for (var type : new String[]{"PHONE", "MOBILE", "WHATSAPP"}) {
            for (var value : new String[]{"+12", "+593999999999", "+123456789012345"}) {
                var id = uuid(insert(type, value, false) + " RETURNING id");
                assertEquals(value, scalar("SELECT value FROM " + TABLE + " WHERE id='" + id + "'"));
            }
            for (var invalid : new String[]{"593999999999", "+012", "+1", "+1234567890123456", "+12AB", "+593 999999999"}) {
                rejects("23514", PREFIX + "phone_format", insert(type, invalid, false));
            }
        }
        // SQL intentionally enforces only nonblank/trimmed EMAIL; semantic email validation belongs to Domain.
        execute(insert("EMAIL", "Mi.Usuario@example.com", false));
        execute(insert("EMAIL", "mi.usuario@example.com", false));
    }

    @Test
    void currentUniquenessPrimaryAndDeletionPreserveHistory() throws Exception {
        var contact = uuid(insert("EMAIL", "Mi.Usuario@example.com", true) + " RETURNING id");
        rejects("23505", "uq_company_site_contacts_current_value", insert("EMAIL", "Mi.Usuario@example.com", false));
        rejects("23505", "uq_company_site_primary_contact", insert("EMAIL", "Other@example.com", true));
        execute(insert("PHONE", "+593999999999", true));
        for (var mutation : new String[]{"deleted_at=now()", "deleted_by='actor'", "deleted_at=now(),deleted_by='  ',is_primary=false",
                "deleted_at=created_at-interval '1 second',deleted_by='actor',is_primary=false", "deleted_at=now(),deleted_by='actor'"}) {
            check(TABLE, contact, PREFIX + "deletion", mutation);
        }
        execute("UPDATE " + TABLE + " SET deleted_at=created_at,deleted_by='Actor-aB',is_primary=false WHERE id='" + contact + "'");
        execute(insert("EMAIL", "Mi.Usuario@example.com", true));
        assertEquals("2", scalar("SELECT count(*) FROM " + TABLE + " WHERE site_id='" + site + "' AND contact_type='EMAIL'"));
        index("uq_company_site_contacts_current_value", "CREATE UNIQUE INDEX uq_company_site_contacts_current_value ON public.company_site_contact_points USING btree (site_id, contact_type, value) WHERE (deleted_at IS NULL)");
        index("uq_company_site_primary_contact", "CREATE UNIQUE INDEX uq_company_site_primary_contact ON public.company_site_contact_points USING btree (site_id, contact_type) WHERE ((is_primary = true) AND (deleted_at IS NULL))");
    }

    @Test
    void originalIdentityIsImmutable() throws Exception {
        var contact = uuid(insert("EMAIL", "Mi.Usuario@example.com", false) + " RETURNING id");
        for (var mutation : new String[]{"id=uuidv7()", "site_id=uuidv7()", "created_at=created_at-interval '1 second'", "created_by='changed'"}) {
            check(TABLE, contact, "ck_company_site_child_immutable_identity", mutation);
        }
        assertEquals("O:reject_company_site_child_identity_change", scalar("SELECT t.tgenabled::text||':'||p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid WHERE t.tgrelid='" + TABLE + "'::regclass AND t.tgname='trg_company_site_contacts_immutable'"));
    }
}
