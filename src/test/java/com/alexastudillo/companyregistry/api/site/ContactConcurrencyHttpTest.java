package com.alexastudillo.companyregistry.api.site;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(ContactHttpProfile.class)
class ContactConcurrencyHttpTest extends ContactHttpSupport {
    @io.quarkus.test.common.http.TestHTTPResource
    URI base;
    private final ObjectMapper json = new ObjectMapper();

    private record Reply(int status, JsonNode body) {
    }

    private Reply send(String path, String method, String body, String key) {
        return send(path, method, body, key, 0);
    }

    private Reply send(String path, String method, String body, String key, long version) {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                    .header("Tenant-Id", tenant.toString()).header("User-Id", "Actor-aB")
                    .header("Process-Id", UUID.randomUUID().toString())
                    .header("If-Match", Long.toString(version)).header("Content-Type", "application/json");
            if (key != null)
                request.header("Idempotency-Key", key);
            var response = client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), json.readTree(response.body()));
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private void blocked(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> scalar("SELECT count(*) FROM pg_locks WHERE NOT granted") >= count);
    }

    @Test
    void primaryAndReclassificationRacesAcceptOneRevisionAndOneEvent() throws Exception {
        for (boolean reclassify : new boolean[] { false, true }) {
            var f = fixture();
            var first = seedContact(f, "MOBILE", reclassify);
            var second = seedContact(f, reclassify ? "WHATSAPP" : "MOBILE", false);
            try (var holder = db(); var statement = holder.createStatement()) {
                holder.setAutoCommit(false);
                statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
                var one = CompletableFuture.supplyAsync(() -> send(f.path() + "/" + first, "PATCH",
                        reclassify ? "{\"contactType\":\"WHATSAPP\"}" : "{\"isPrimary\":true}", "one-" + f.site()));
                blocked(1);
                var two = CompletableFuture.supplyAsync(
                        () -> send(f.path() + "/" + second, "PATCH", "{\"isPrimary\":true}", "two-" + f.site()));
                blocked(2);
                holder.rollback();
                var statuses = List.of(one.get(10, TimeUnit.SECONDS).status(), two.get(10, TimeUnit.SECONDS).status());
                assertTrue(statuses.contains(200));
                assertTrue(statuses.contains(412));
            }
            assertEquals(1, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
            assertEquals(1, scalar("SELECT count(*) FROM company_site_contact_points WHERE site_id='" + f.site()
                    + "' AND is_primary AND contact_type='" + (reclassify ? "WHATSAPP" : "MOBILE") + "'"));
            assertEquals(1, scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
        }
    }

    @Test
    void concurrentPrimaryPostsAcceptExactlyOneRevision() throws Exception {
        var f = fixture();
        try (var holder = db(); var statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
            var one = CompletableFuture.supplyAsync(() -> send(f.path(), "POST",
                    "{\"contactType\":\"EMAIL\",\"value\":\"one@example.com\",\"isPrimary\":true}", "one"));
            blocked(1);
            var two = CompletableFuture.supplyAsync(() -> send(f.path(), "POST",
                    "{\"contactType\":\"EMAIL\",\"value\":\"two@example.com\",\"isPrimary\":true}", "two"));
            blocked(2);
            holder.rollback();
            assertEquals(Set.of(201, 412),
                    Set.of(one.get(12, TimeUnit.SECONDS).status(), two.get(12, TimeUnit.SECONDS).status()));
        }
        assertEquals(1, scalar(
                "SELECT count(*) FROM company_site_contact_points WHERE site_id='" + f.site() + "' AND is_primary"));
        assertEquals(1, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
    }

    @Test
    void committedArchiveAndSiteCloseUseCurrentStateAfterLockWait() throws Exception {
        for (boolean archived : new boolean[] { true, false }) {
            var f = fixture();
            var contact = seedContact(f, "EMAIL", false);
            try (var holder = db(); var statement = holder.createStatement()) {
                holder.setAutoCommit(false);
                statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
                statement.execute("SELECT id FROM company_sites WHERE id='" + f.site() + "' FOR UPDATE");
                var pending = CompletableFuture
                        .supplyAsync(() -> send(f.path() + "/" + contact, "PATCH", "{\"label\":\"NEW\"}", "pending"));
                blocked(1);
                if (archived)
                    statement.execute(
                            "UPDATE companies SET status='ARCHIVED',version=version+1 WHERE id='" + f.company() + "'");
                else
                    statement.execute(
                            "UPDATE company_sites SET status='CLOSED',version=version+1 WHERE id='" + f.site() + "'");
                holder.commit();
                var result = pending.get(12, TimeUnit.SECONDS);
                assertEquals(archived ? 409 : 412, result.status());
                assertEquals(archived ? "site-mutation-not-allowed-for-archived-company" : "aggregate-version-mismatch",
                        result.body().path("code").asText());
            }
            assertEquals(0, scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                    + "' AND idempotency_key='pending'"));
        }
    }

    @Test
    void realCloseAndArchiveRequestsSerializeWithContactAcceptances() throws Exception {
        for (String operation : List.of("close", "archive")) {
            for (boolean terminalFirst : new boolean[] { true, false }) {
                var f = fixture();
                var contact = seedContact(f, "EMAIL", false);
                String terminalPath = operation.equals("close") ? f.path().replace("/contacts", "/close")
                        : "/v1/companies/" + f.company() + "/archive";
                try (var holder = db(); var statement = holder.createStatement()) {
                    holder.setAutoCommit(false);
                    statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
                    CompletableFuture<Reply> terminal;
                    CompletableFuture<Reply> child;
                    if (terminalFirst) {
                        terminal = CompletableFuture
                                .supplyAsync(() -> send(terminalPath, "POST", "{}", "terminal-" + f.site()));
                        blocked(1);
                        child = CompletableFuture.supplyAsync(() -> send(f.path() + "/" + contact, "PATCH",
                                "{\"label\":\"UPDATED\"}", "child-" + f.site()));
                    } else {
                        child = CompletableFuture.supplyAsync(() -> send(f.path() + "/" + contact, "PATCH",
                                "{\"label\":\"UPDATED\"}", "child-" + f.site()));
                        blocked(1);
                        terminal = CompletableFuture
                                .supplyAsync(() -> send(terminalPath, "POST", "{}", "terminal-" + f.site()));
                    }
                    blocked(2);
                    holder.rollback();
                    var terminalResult = terminal.get(12, TimeUnit.SECONDS);
                    var childResult = child.get(12, TimeUnit.SECONDS);
                    if (operation.equals("close")) {
                        assertEquals(Set.of(200, 412), Set.of(terminalResult.status(), childResult.status()));
                    } else {
                        assertEquals(200, terminalResult.status(), terminalResult.body().toString());
                        assertEquals(terminalFirst ? 409 : 200, childResult.status(), childResult.body().toString());
                    }
                }
            }
        }
    }

    @Test
    void sameKeyReturnsOneAcceptanceAndCrossCompanyReuseNeverLeaksASnapshot() throws Exception {
        var f = fixture();
        var contact = seedContact(f, "MOBILE", false);
        var path = f.path() + "/" + contact;
        var one = CompletableFuture.supplyAsync(() -> send(path, "PATCH", "{\"isPrimary\":true}", "same"));
        var two = CompletableFuture.supplyAsync(() -> send(path, "PATCH", "{\"isPrimary\":true}", "same"));
        var first = one.get(12, TimeUnit.SECONDS);
        var second = two.get(12, TimeUnit.SECONDS);
        assertEquals(200, first.status());
        assertEquals(200, second.status());
        assertEquals(first.body().path("data"), second.body().path("data"));
        assertEquals(1, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
        var a = fixture();
        var b = fixture();
        var aid = seedContact(a, "MOBILE", false);
        var bid = seedContact(b, "MOBILE", false);
        var left = CompletableFuture
                .supplyAsync(() -> send(a.path() + "/" + aid, "PATCH", "{\"isPrimary\":true}", "cross-company"));
        var right = CompletableFuture
                .supplyAsync(() -> send(b.path() + "/" + bid, "PATCH", "{\"isPrimary\":true}", "cross-company"));
        var l = left.get(12, TimeUnit.SECONDS);
        var r = right.get(12, TimeUnit.SECONDS);
        assertEquals(Set.of(200, 409), Set.of(l.status(), r.status()));
        var rejected = l.status() == 409 ? l : r;
        assertEquals("idempotency-key-already-used-with-different-request", rejected.body().path("code").asText());
        assertEquals(2, rejected.body().size());
        assertEquals(1,
                scalar("SELECT sum(version) FROM company_sites WHERE id IN ('" + a.site() + "','" + b.site() + "')"));
        assertEquals(1, scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id IN ('" + a.site() + "','"
                + b.site() + "')"));
    }

    @Test
    void hu05AndAnotherChildWriterShareSiteVersionAndCompanySiteLockOrder() throws Exception {
        var f = fixture();
        var contact = seedContact(f, "MOBILE", false);
        try (var holder = db(); var statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
            var contactWrite = CompletableFuture
                    .supplyAsync(() -> send(f.path() + "/" + contact, "PATCH", "{\"isPrimary\":true}", "contact"));
            blocked(1);
            var siteWrite = CompletableFuture.supplyAsync(
                    () -> send(f.path().replace("/contacts", ""), "PATCH", "{\"name\":\"CHANGED\"}", "site"));
            blocked(2);
            holder.rollback();
            assertEquals(Set.of(200, 412), Set.of(contactWrite.get(12, TimeUnit.SECONDS).status(),
                    siteWrite.get(12, TimeUnit.SECONDS).status()));
        }
        var child = fixture();
        var childContact = seedContact(child, "MOBILE", false);
        try (var writer = db(); var statement = writer.createStatement()) {
            writer.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE id='" + child.company() + "' FOR UPDATE");
            statement.execute("SELECT id FROM company_sites WHERE id='" + child.site() + "' FOR UPDATE");
            var competing = CompletableFuture.supplyAsync(
                    () -> send(child.path() + "/" + childContact, "PATCH", "{\"isPrimary\":true}", "other-child"));
            blocked(1);
            statement.execute(
                    "INSERT INTO company_site_addresses(site_id,country_code,address_line_1,created_by,updated_by,created_at,updated_at) VALUES('"
                            + child.site()
                            + "','EC','AVENIDA','OtherChild','OtherChild',transaction_timestamp(),transaction_timestamp())");
            statement.execute(
                    "UPDATE company_sites SET version=version+1,updated_by='OtherChild',updated_at=transaction_timestamp() WHERE id='"
                            + child.site() + "'");
            statement.execute(
                    "INSERT INTO company_outbox_events(tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,"
                            + "occurred_at,correlation_id,created_by,updated_by,created_at,updated_at) SELECT tenant_id,'COMPANY_SITE',id,version,"
                            + "'company.site.updated.v1',jsonb_build_object('siteId',id,'companyId',company_id,'code',code,'name',name,"
                            + "'siteType',site_type,'status',status,'updatedAt',to_char(updated_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),"
                            + "'updatedBy',updated_by),updated_at,'" + UUID.randomUUID()
                            + "','OtherChild','OtherChild',updated_at,updated_at "
                            + "FROM company_sites WHERE id='" + child.site() + "'");
            writer.commit();
            assertEquals(412, competing.get(12, TimeUnit.SECONDS).status());
        }
        assertEquals(1, scalar("SELECT version FROM company_sites WHERE id='" + child.site() + "'"));
        assertEquals(1, scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + child.site() + "'"));
        assertEquals(1,
                scalar("SELECT count(*) FROM company_site_addresses c JOIN company_sites s ON s.id=c.site_id "
                        + "WHERE s.id='" + child.site()
                        + "' AND c.updated_at=s.updated_at AND c.updated_by=s.updated_by"));
        assertEquals(0, scalar("SELECT version FROM companies WHERE id='" + child.company() + "'"));
    }

    @Test
    void injectedCompletionAndOutboxFailuresRollBackPrimaryDemotionContactSiteAndAcceptance() throws Exception {
        var f = fixture();
        var contact = seedContact(f, "MOBILE", true);
        for (String table : List.of("company_site_contact_points", "company_sites", "company_idempotency_records",
                "company_outbox_events")) {
            var name = "hu07_fail_" + UUID.randomUUID().toString().replace("-", "");
            String diagnostic = table.equals("company_site_contact_points")
                    ? "ERRCODE='23505', CONSTRAINT='uq_company_site_primary_contact'"
                    : "ERRCODE='23514'";
            sql("CREATE FUNCTION " + name
                    + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-hu07-fault' USING "
                    + diagnostic + "; END $$");
            String action = table.equals("company_sites") ? "UPDATE" : "INSERT";
            String scope = table.equals("company_site_contact_points") ? "NEW.site_id='" + f.site() + "'"
                    : "NEW.tenant_id='" + tenant + "'";
            sql("CREATE TRIGGER " + name + " BEFORE " + action + " ON " + table + " FOR EACH ROW WHEN (" + scope
                    + ") EXECUTE FUNCTION " + name + "()");
            try {
                var body = "{\"contactType\":\"MOBILE\",\"value\":\"+12025550123\",\"isPrimary\":true}";
                var failed = send(f.path(), "POST", body, "rollback");
                assertEquals(500, failed.status());
                assertEquals("server-error", failed.body().path("code").asText());
                assertEquals(2, failed.body().size());
                assertEquals(0, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
                assertEquals(1, scalar("SELECT count(*) FROM company_site_contact_points WHERE site_id='" + f.site()
                        + "' AND is_primary AND id='" + contact + "' AND updated_by='Creator'"));
                assertEquals(1,
                        scalar("SELECT count(*) FROM company_site_contact_points WHERE site_id='" + f.site() + "'"));
                assertEquals(0,
                        scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
                assertEquals(0,
                        scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
            } finally {
                sql("DROP TRIGGER " + name + " ON " + table);
                sql("DROP FUNCTION " + name + "()");
            }
        }
    }

    @Test
    void parallelCreationAndDeletionWithSameKeysProduceOneEffectEach() throws Exception {
        var f = fixture();
        var body = "{\"contactType\":\"MOBILE\",\"value\":\"+12025550123\"}";
        var first = CompletableFuture.supplyAsync(() -> send(f.path(), "POST", body, "create"));
        var second = CompletableFuture.supplyAsync(() -> send(f.path(), "POST", body, "create"));
        var a = first.get(12, TimeUnit.SECONDS);
        var b = second.get(12, TimeUnit.SECONDS);
        assertEquals(201, a.status());
        assertEquals(201, b.status());
        assertEquals(a.body().path("data"), b.body().path("data"));
        var detail = f.path() + "/" + a.body().path("data").path("contactId").asText();
        var left = CompletableFuture.supplyAsync(() -> send(detail, "DELETE", "{}", "delete", 1));
        var right = CompletableFuture.supplyAsync(() -> send(detail, "DELETE", "{}", "delete", 1));
        var l = left.get(12, TimeUnit.SECONDS);
        var r = right.get(12, TimeUnit.SECONDS);
        assertEquals(200, l.status());
        assertEquals(200, r.status());
        assertEquals(l.body().path("data"), r.body().path("data"));
        assertEquals(2, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
        assertEquals(2, scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
        assertEquals(2, scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant + "'"));
    }

    @Test
    void currentDetailAndPageStayCoherentAcrossContactAddressAndSiteWriters() throws Exception {
        var f = fixture();
        var contact = seedContact(f, "EMAIL", false);
        UUID address;
        try (var c = db();
                var statement = c.createStatement();
                var row = statement.executeQuery(
                        "INSERT INTO company_site_addresses(site_id,country_code,address_line_1,created_by,updated_by) VALUES('"
                                + f.site() + "','EC','AVENIDA','Creator','Creator') RETURNING id")) {
            row.next();
            address = row.getObject(1, UUID.class);
        }
        var writer = CompletableFuture.runAsync(() -> {
            for (int version = 0; version < 9; version++) {
                String path;
                String body;
                if (version % 3 == 0) {
                    path = f.path() + "/" + contact;
                    body = "{\"label\":\"CONTACT " + version + "\"}";
                } else if (version % 3 == 1) {
                    path = f.path().replace("/contacts", "/addresses/") + address;
                    body = "{\"houseNumber\":\"HOUSE " + version + "\"}";
                } else {
                    path = f.path().replace("/contacts", "");
                    body = "{\"name\":\"SITE " + version + "\"}";
                }
                var response = send(path, "PATCH", body, null, version);
                assertEquals(200, response.status(), response.body().toString());
            }
        });
        for (int round = 0; round < 12; round++) {
            var detail = request().get(f.path() + "/" + contact).then().statusCode(200).extract().jsonPath();
            int revision = detail.getInt("data.siteVersion");
            String label = detail.getString("data.label");
            if (revision > 0)
                assertEquals("CONTACT " + ((revision - 1) / 3 * 3), label);
            var page = request().get(f.path()).then().statusCode(200).extract().jsonPath();
            int pageRevision = page.getInt("data[0].siteVersion");
            if (pageRevision > 0)
                assertEquals("CONTACT " + ((pageRevision - 1) / 3 * 3), page.getString("data[0].label"));
        }
        writer.get(15, TimeUnit.SECONDS);
        assertEquals(9, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
    }

    @Test
    void eachPageReadsOneCurrentSharedRevisionDuringConcurrentSiteMutations() throws Exception {
        var f = fixture();
        for (int i = 0; i < 10; i++)
            seedContact(f, "MOBILE", false);
        var writer = CompletableFuture.runAsync(() -> {
            for (int version = 0; version < 10; version++) {
                var result = send(f.path().replace("/contacts", ""), "PATCH", "{\"name\":\"NAME " + version + "\"}",
                        null, version);
                assertEquals(200, result.status(), result.body().toString());
            }
        });
        for (int page = 0; page < 10; page++) {
            var response = request().get(f.path()).then().statusCode(200).extract().jsonPath();
            var versions = response.getList("data.siteVersion", Integer.class);
            assertEquals(10, versions.size());
            assertEquals(1, new HashSet<>(versions).size());
        }
        writer.get(15, TimeUnit.SECONDS);
        assertEquals(10, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
    }
}
