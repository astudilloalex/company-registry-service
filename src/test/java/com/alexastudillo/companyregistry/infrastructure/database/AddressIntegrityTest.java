package com.alexastudillo.companyregistry.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class AddressIntegrityTest extends SqlIntegritySupport {
    private static final String TABLE = "company_site_addresses";
    private static final String PREFIX = "ck_company_site_addresses_";

    private String insert() {
        return "INSERT INTO company_site_addresses(site_id,country_code,address_line_1,created_by,updated_by) "
                + "VALUES('" + site + "','EC','DIRECCIÓN','actor','actor')";
    }

    @Test
    void keysOwnershipDefaultsAndTypes() throws Exception {
        var address = uuid(insert() + " RETURNING id");
        assertEquals(7, address.version());
        assertEquals("PHYSICAL:false", scalar("SELECT address_type::text||':'||is_primary::text FROM " + TABLE + " WHERE id='" + address + "'"));
        rejects("23505", "pk_company_site_addresses", "INSERT INTO " + TABLE + " SELECT * FROM " + TABLE + " WHERE id='" + address + "'");
        rejects("23503", "fk_company_site_addresses_site", insert().replace(site.toString(), UUID.randomUUID().toString()));
        rejects("23001", "fk_company_site_addresses_site", "DELETE FROM company_sites WHERE id='" + site + "'");
        assertEquals("PHYSICAL,MAILING", scalar("SELECT string_agg(enumlabel,',' ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid='company_site_address_type'::regtype"));
        execute("UPDATE " + TABLE + " SET address_type='MAILING' WHERE id='" + address + "'");
        rejects("22P02", null, "UPDATE " + TABLE + " SET address_type='UNKNOWN' WHERE id='" + address + "'");
        for (var column : new String[]{"site_id", "country_code", "address_line_1", "address_type", "is_primary", "created_by", "updated_by", "created_at", "updated_at"}) {
            // INSERT bypasses identity protection to exercise NOT NULL directly.
            rejects("23502", null, "INSERT INTO " + TABLE + " SELECT "
                    + "uuidv7()," + (column.equals("site_id") ? "NULL" : "site_id") + ","
                    + java.util.stream.Stream.of("address_type", "country_code", "administrative_division_code", "address_line_1", "address_line_2",
                            "house_number", "postal_code", "latitude", "longitude", "is_primary", "created_at", "created_by", "updated_at", "updated_by", "deleted_at", "deleted_by")
                            .map(c -> c.equals(column) ? "NULL" : c).collect(java.util.stream.Collectors.joining(","))
                    + " FROM " + TABLE + " WHERE id='" + address + "'");
        }
    }

    @Test
    void everyTextAndAuditCheckHasPositiveAndNegativeCases() throws Exception {
        var address = uuid(insert() + " RETURNING id");
        for (var country : new String[]{"''", "'eC'", "'12'", "'E'"}) {
            check(TABLE, address, PREFIX + "country", "country_code=" + country);
        }
        for (var pair : new String[][]{{"division", "administrative_division_code"}, {"line1", "address_line_1"},
                {"line2", "address_line_2"}, {"house_number", "house_number"}, {"postal_code", "postal_code"}}) {
            canonical(TABLE, address, PREFIX + pair[0], pair[1], !pair[0].equals("line1"));
        }
        rejects("23514", PREFIX + "created_by", insert().replace("'actor','actor'", "'  ','actor'"));
        check(TABLE, address, PREFIX + "updated_by", "updated_by='  '");
        check(TABLE, address, PREFIX + "audit_dates", "updated_at=created_at-interval '1 second'");
        execute("UPDATE " + TABLE + " SET updated_by='Actor-aB',updated_at=created_at+interval '1 second' WHERE id='" + address + "'");
        assertEquals("Actor-aB", scalar("SELECT updated_by FROM " + TABLE + " WHERE id='" + address + "'"));
    }

    @Test
    void coordinatePairsAndInclusiveBounds() throws Exception {
        var address = uuid(insert() + " RETURNING id");
        assertEquals("true", scalar("SELECT (latitude IS NULL AND longitude IS NULL)::text FROM " + TABLE + " WHERE id='" + address + "'"));
        check(TABLE, address, PREFIX + "coordinate_pair", "latitude=0");
        check(TABLE, address, PREFIX + "coordinate_pair", "longitude=0");
        for (var pair : new String[]{"latitude=-90,longitude=-180", "latitude=90,longitude=180", "latitude=-0.123456,longitude=78.123456"}) {
            execute("UPDATE " + TABLE + " SET " + pair + " WHERE id='" + address + "'");
        }
        assertEquals("-0.123456:78.123456", scalar("SELECT latitude::text||':'||longitude::text FROM " + TABLE + " WHERE id='" + address + "'"));
        for (var latitude : new String[]{"-90.000001", "90.000001"}) check(TABLE, address, PREFIX + "latitude", "latitude=" + latitude);
        for (var longitude : new String[]{"-180.000001", "180.000001"}) check(TABLE, address, PREFIX + "longitude", "longitude=" + longitude);
        execute("UPDATE " + TABLE + " SET latitude=NULL,longitude=NULL WHERE id='" + address + "'");
    }

    @Test
    void partialPrimaryAndCoherentDeletionKeepHistory() throws Exception {
        var address = uuid(insert() + " RETURNING id");
        execute("UPDATE " + TABLE + " SET is_primary=true WHERE id='" + address + "'");
        rejects("23505", "uq_company_site_primary_address", insert().replace("created_by,updated_by", "created_by,updated_by,is_primary").replace("'actor','actor')", "'actor','actor',true)"));
        execute(insert().replace("site_id,country_code", "site_id,address_type,country_code").replace("','EC'", "','MAILING','EC'").replace("created_by,updated_by", "created_by,updated_by,is_primary").replace("'actor','actor')", "'actor','actor',true)"));
        for (var mutation : new String[]{"deleted_at=now()", "deleted_by='actor'", "deleted_at=now(),deleted_by='  ',is_primary=false",
                "deleted_at=created_at-interval '1 second',deleted_by='actor',is_primary=false", "deleted_at=now(),deleted_by='actor'"}) {
            check(TABLE, address, PREFIX + "deletion", mutation);
        }
        execute("UPDATE " + TABLE + " SET deleted_at=created_at,deleted_by='Actor-aB',is_primary=false WHERE id='" + address + "'");
        execute(insert().replace("created_by,updated_by", "created_by,updated_by,is_primary").replace("'actor','actor')", "'actor','actor',true)"));
        assertEquals("2", scalar("SELECT count(*) FROM " + TABLE + " WHERE site_id='" + site + "' AND address_type='PHYSICAL'"));
        index("ix_company_site_addresses_current", "CREATE INDEX ix_company_site_addresses_current ON public.company_site_addresses USING btree (site_id, address_type) WHERE (deleted_at IS NULL)");
        index("uq_company_site_primary_address", "CREATE UNIQUE INDEX uq_company_site_primary_address ON public.company_site_addresses USING btree (site_id, address_type) WHERE ((is_primary = true) AND (deleted_at IS NULL))");
    }

    @Test
    void childIdentityCannotBeReassigned() throws Exception {
        var address = uuid(insert() + " RETURNING id");
        for (var mutation : new String[]{"id=uuidv7()", "site_id=uuidv7()", "created_at=created_at-interval '1 second'", "created_by='changed'"}) {
            check(TABLE, address, "ck_company_site_child_immutable_identity", mutation);
        }
        assertEquals("O:reject_company_site_child_identity_change", scalar("SELECT t.tgenabled::text||':'||p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid WHERE t.tgrelid='" + TABLE + "'::regclass AND t.tgname='trg_company_site_addresses_immutable'"));
    }
}
