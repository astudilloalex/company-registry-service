package com.alexastudillo.companyregistry.api.site;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ContactHttpProfile.class)
class ContactHttpTest extends ContactHttpSupport {
    private static final String MINIMUM = "{\"contactType\":\" email \",\"value\":\" Sales@Example.COM \"}";

    @Test
    void creationValueOnlyPatchNoOpDeletionAndReplayPreserveAcceptedResults() throws Exception {
        var f = fixture();
        var created = mutation(0, "original").body(MINIMUM).post(f.path()).then().statusCode(201)
                .header("Process-Id", process).body("data.siteVersion", equalTo(1), "data.isPrimary", equalTo(false),
                        "data.value", equalTo("sales@example.com"), "data.label", nullValue())
                .extract().jsonPath().getMap("data");
        assertEquals(10, created.size());
        String detail = f.path() + "/" + created.get("contactId");
        mutation(1, "noop").body("{}").patch(detail).then().statusCode(200).body("data", equalTo(created));
        var updated = mutation(1, "update").body("{\"value\":\" Changed@Example.com \"}").patch(detail)
                .then().statusCode(200).body("data.siteVersion", equalTo(2), "data.value", equalTo("changed@example.com"))
                .extract().jsonPath().getMap("data");
        request().get(detail).then().statusCode(200).body("data", equalTo(updated));
        mutation(2, "reclassify").body("{\"contactType\":\"WHATSAPP\",\"value\":\"+12025550123\"}").patch(detail)
                .then().statusCode(200).body("data.siteVersion", equalTo(3));
        var receipt = mutation(3, "delete").delete(detail).then().statusCode(200).body("data.siteVersion", equalTo(4))
                .extract().jsonPath().getMap("data");
        assertEquals(Set.of("contactId", "siteId", "siteVersion", "deletedAt", "deletedBy"), receipt.keySet());
        request().get(detail).then().statusCode(404).body("code", equalTo("contact-not-found"));
        mutation(0, null).body("{}").patch(detail).then().statusCode(404).body("code", equalTo("contact-not-found"));
        sql("UPDATE company_sites SET status='CLOSED' WHERE id='" + f.site() + "'");
        sql("UPDATE companies SET status='ARCHIVED' WHERE id='" + f.company() + "'");
        mutation(0, "original").body(MINIMUM).post(f.path()).then().statusCode(201).body("data", equalTo(created));
        mutation(1, "noop").body("{}").patch(detail).then().statusCode(200).body("data", equalTo(created));
        mutation(1, "update").body("{\"value\":\"changed@EXAMPLE.COM\"}").patch(detail).then().statusCode(200).body("data", equalTo(updated));
        mutation(3, "delete").delete(detail).then().statusCode(200).body("data", equalTo(receipt));
        mutation(0, "original").body("{\"contactType\":\"EMAIL\",\"value\":\"different@example.com\"}").post(f.path())
                .then().statusCode(409).body("code", equalTo("idempotency-key-already-used-with-different-request"));
        mutation(0, "original").body("{\"contactType\":\"MOBILE\",\"value\":\"+442079460018\"}").post(f.path())
                .then().statusCode(409).body("code", equalTo("idempotency-key-already-used-with-different-request"));
        mutation(4, "new-fixed").body("{\"contactType\":\"MOBILE\",\"value\":\"+442079460018\"}").post(f.path())
                .then().statusCode(409).body("code", equalTo("site-mutation-not-allowed-for-archived-company"));
        assertEquals(4, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
        assertEquals(0, scalar("SELECT version FROM companies WHERE id='" + f.company() + "'"));
        assertEquals(4, scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
        assertEquals(1, scalar("SELECT count(*) FROM company_site_contact_points WHERE site_id='" + f.site() + "' AND deleted_at IS NOT NULL"));
    }

    @Test
    void reclassificationDemotesDestinationAndDuplicatesLeaveAllRowsIntact() throws Exception {
        var f = fixture();
        var mobile = seedContact(f, "MOBILE", "+12025550123", true);
        var whatsapp = seedContact(f, "WHATSAPP", "+447911123456", true);
        mutation(0, null).body("{\"contactType\":\"WHATSAPP\",\"value\":\"+447911123456\"}")
                .patch(f.path() + "/" + mobile).then().statusCode(409).body("code", equalTo("contact-value-already-exists"));
        assertEquals(2, scalar("SELECT count(*) FROM company_site_contact_points WHERE site_id='" + f.site() + "' AND is_primary"));
        var moved = mutation(0, "move").body("{\"contactType\":\"whatsapp\"}").patch(f.path() + "/" + mobile)
                .then().statusCode(200).body("data.isPrimary", equalTo(true), "data.siteVersion", equalTo(1))
                .extract().jsonPath().getMap("data");
        request().get(f.path() + "/" + whatsapp).then().statusCode(200).body("data.isPrimary", equalTo(false),
                "data.updatedAt", equalTo(moved.get("updatedAt")), "data.updatedBy", equalTo("Actor-aB"));
        request().queryParam("contactType", "MOBILE").queryParam("isPrimary", "true").get(f.path())
                .then().statusCode(200).body("data", empty());
        mutation(1, null).body("{\"isPrimary\":false}").patch(f.path() + "/" + mobile).then().statusCode(200);
        request().queryParam("isPrimary", "true").get(f.path()).then().statusCode(200).body("data", empty());
        assertEquals(2, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
    }

    @Test
    void fourChannelsFiltersAndAuthenticatedPaginationUseCurrentSharedVersion() throws Exception {
        var f = fixture();
        seedContact(f, "EMAIL", "a@example.com", true);
        seedContact(f, "PHONE", "+12025550123", true);
        seedContact(f, "MOBILE", "+12025550123", true);
        seedContact(f, "WHATSAPP", "+12025550123", true);
        var first = request().queryParam("limit", 1).get(f.path()).then().statusCode(200)
                .body("numberOfElements", equalTo(1)).extract().jsonPath();
        String cursor = first.getString("nextCursor");
        assertNotNull(cursor);
        var second = request().queryParam("limit", 3).queryParam("cursor", cursor).get(f.path()).then().statusCode(200)
                .body("numberOfElements", equalTo(3), "nextCursor", nullValue()).extract().jsonPath().getList("data.contactId");
        assertFalse(second.contains(first.getString("data[0].contactId")));
        request().queryParam("contactType", "MOBILE").queryParam("isPrimary", true).get(f.path()).then()
                .statusCode(200).body("numberOfElements", equalTo(1));
        var other = fixture();
        request().queryParam("cursor", cursor).get(other.path()).then().statusCode(400)
                .body("code", equalTo("contact-cursor-invalid-or-incompatible"));
    }

    @Test
    void errorsRespectFieldPhasesThenOwnershipVersionAndState() throws Exception {
        var f = fixture();
        var contact = seedContact(f, "PHONE", "+442079460018", false);
        mutation(0, "bad").body("{\"contactType\":\"FAX\",\"value\":3}").post(f.path()).then()
                .statusCode(400).body("code", equalTo("contact-value-must-be-string"));
        mutation(0, "fixed").body("{\"contactType\":\"MOBILE\",\"value\":\"+442079460018\"}").post(f.path())
                .then().statusCode(400).body("code", equalTo("contact-mobile-number-required"));
        mutation(0, "unicode").body("{\"contactType\":\"EMAIL\",\"value\":\"K@example.com\"}").post(f.path())
                .then().statusCode(400).body("code", equalTo("contact-value-invalid"));
        sql("UPDATE company_sites SET status='CLOSED' WHERE id='" + f.site() + "'");
        mutation(1, null).body("{\"contactType\":\"EMAIL\"}").patch(f.path() + "/" + contact).then()
                .statusCode(412).body("code", equalTo("aggregate-version-mismatch"));
        mutation(0, null).body("{}").patch(f.path() + "/" + contact).then().statusCode(409).body("code", equalTo("site-closed"));
        assertEquals(0, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
    }
}
