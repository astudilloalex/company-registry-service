package com.alexastudillo.companyregistry.api.site;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
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
@io.quarkus.test.junit.TestProfile(AddressHttpProfile.class)
class AddressConcurrencyHttpTest extends AddressHttpSupport {
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
            var first = seedAddress(f, "PHYSICAL", reclassify);
            var second = seedAddress(f, reclassify ? "MAILING" : "PHYSICAL", false);
            try (var holder = db(); var statement = holder.createStatement()) {
                holder.setAutoCommit(false);
                statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
                var one = CompletableFuture.supplyAsync(() -> send(f.path() + "/" + first, "PATCH",
                        reclassify ? "{\"addressType\":\"MAILING\"}" : "{\"isPrimary\":true}", "one-" + f.site()));
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
            assertEquals(1, scalar("SELECT count(*) FROM company_site_addresses WHERE site_id='" + f.site()
                    + "' AND is_primary AND address_type='" + (reclassify ? "MAILING" : "PHYSICAL") + "'"));
            assertEquals(1, scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + f.site() + "'"));
        }
    }

    @Test
    void sameKeyReturnsOneAcceptanceAndCrossCompanyReuseNeverLeaksASnapshot() throws Exception {
        var f = fixture();
        var address = seedAddress(f, "PHYSICAL", false);
        var path = f.path() + "/" + address;
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
        var aid = seedAddress(a, "PHYSICAL", false);
        var bid = seedAddress(b, "PHYSICAL", false);
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
        var address = seedAddress(f, "PHYSICAL", false);
        try (var holder = db(); var statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
            var addressWrite = CompletableFuture
                    .supplyAsync(() -> send(f.path() + "/" + address, "PATCH", "{\"isPrimary\":true}", "address"));
            blocked(1);
            var siteWrite = CompletableFuture.supplyAsync(
                    () -> send(f.path().replace("/addresses", ""), "PATCH", "{\"name\":\"CHANGED\"}", "site"));
            blocked(2);
            holder.rollback();
            assertEquals(Set.of(200, 412), Set.of(addressWrite.get(12, TimeUnit.SECONDS).status(),
                    siteWrite.get(12, TimeUnit.SECONDS).status()));
        }
        var child = fixture();
        var childAddress = seedAddress(child, "PHYSICAL", false);
        try (var writer = db(); var statement = writer.createStatement()) {
            writer.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE id='" + child.company() + "' FOR UPDATE");
            statement.execute("SELECT id FROM company_sites WHERE id='" + child.site() + "' FOR UPDATE");
            var competing = CompletableFuture.supplyAsync(
                    () -> send(child.path() + "/" + childAddress, "PATCH", "{\"isPrimary\":true}", "other-child"));
            blocked(1);
            statement.execute(
                    "INSERT INTO company_site_contact_points(site_id,contact_type,value,created_by,updated_by,created_at,updated_at) VALUES('"
                            + child.site()
                            + "','EMAIL','case@example.com','OtherChild','OtherChild',transaction_timestamp(),transaction_timestamp())");
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
                scalar("SELECT count(*) FROM company_site_contact_points c JOIN company_sites s ON s.id=c.site_id "
                        + "WHERE s.id='" + child.site()
                        + "' AND c.updated_at=s.updated_at AND c.updated_by=s.updated_by"));
        assertEquals(0, scalar("SELECT version FROM companies WHERE id='" + child.company() + "'"));
    }

    @Test
    void remoteWaitHoldsNoDatabaseLocksAndCurrentDeletionRevisionOrReplayWinOverRemoteFailure() throws Exception {
        for (var scenario : List.of("deleted", "revision", "replay", "archived")) {
            var f = fixture();
            var address = seedAddress(f, "PHYSICAL", false);
            var stub = AddressReferenceResource.stub;
            stub.reset();
            var country = UUID.randomUUID();
            country("CO", country, "ACTIVE");
            var gate = stub.holdNext("/api/v1/countries/by-alpha2/CO");
            var detail = f.path() + "/" + address;
            var key = "remote-" + f.site();
            var pending = CompletableFuture.supplyAsync(() -> send(detail, "PATCH", "{\"countryCode\":\"CO\"}", key));
            assertTrue(gate.arrived().await(5, TimeUnit.SECONDS));
            try (var c = db(); var statement = c.createStatement()) {
                c.setAutoCommit(false);
                statement.execute("SET LOCAL lock_timeout='500ms'");
                statement.execute("SELECT id FROM companies WHERE id='" + f.company() + "' FOR UPDATE");
                statement.execute("SELECT id FROM company_sites WHERE id='" + f.site() + "' FOR UPDATE");
                c.rollback();
            }
            Reply concurrent = switch (scenario) {
                case "deleted" -> send(detail, "DELETE", "{}", "deletion-" + f.site());
                case "revision" ->
                    send(f.path().replace("/addresses", ""), "PATCH", "{\"name\":\"NEW NAME\"}", "site-" + f.site());
                case "archived" ->
                    send("/v1/companies/" + f.company() + "/archive", "POST", "{}", "archive-" + f.site());
                default -> send(detail, "PATCH", "{\"countryCode\":\"CO\"}", key);
            };
            assertEquals(200, concurrent.status(), concurrent.body().toString());
            gate.reply().complete(new ReferenceStubServer.Reply(503, "{}", 0));
            var outcome = pending.get(10, TimeUnit.SECONDS);
            int expected = switch (scenario) {
                case "deleted" -> 404;
                case "revision" -> 412;
                case "archived" -> 409;
                default -> 200;
            };
            assertEquals(expected, outcome.status(), outcome.body().toString());
            if (scenario.equals("replay"))
                assertEquals(concurrent.body().path("data"), outcome.body().path("data"));
        }
    }

    @Test
    void injectedCompletionAndOutboxFailuresRollBackPrimaryDemotionAddressSiteAndAcceptance() throws Exception {
        var f = fixture();
        var address = seedAddress(f, "PHYSICAL", true);
        var stub = AddressReferenceResource.stub;
        stub.reset();
        country("EC", UUID.randomUUID(), "ACTIVE");
        for (String table : List.of("company_idempotency_records", "company_outbox_events")) {
            var name = "hu06_fail_" + UUID.randomUUID().toString().replace("-", "");
            sql("CREATE FUNCTION " + name
                    + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-hu06-fault' USING ERRCODE='23514'; END $$");
            sql("CREATE TRIGGER " + name + " BEFORE INSERT ON " + table + " FOR EACH ROW WHEN (NEW.tenant_id='" + tenant
                    + "') EXECUTE FUNCTION " + name + "()");
            try {
                var body = "{\"addressType\":\"PHYSICAL\",\"countryCode\":\"EC\",\"addressLine1\":\"NEW\",\"isPrimary\":true}";
                var failed = send(f.path(), "POST", body, "rollback");
                assertEquals(500, failed.status());
                assertEquals("server-error", failed.body().path("code").asText());
                assertEquals(2, failed.body().size());
                assertEquals(0, scalar("SELECT version FROM company_sites WHERE id='" + f.site() + "'"));
                assertEquals(1, scalar("SELECT count(*) FROM company_site_addresses WHERE site_id='" + f.site()
                        + "' AND is_primary AND id='" + address + "' AND updated_by='Creator'"));
                assertEquals(1, scalar("SELECT count(*) FROM company_site_addresses WHERE site_id='" + f.site() + "'"));
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
        var stub = AddressReferenceResource.stub;
        stub.reset();
        country("EC", UUID.randomUUID(), "ACTIVE");
        var body = "{\"addressType\":\"PHYSICAL\",\"countryCode\":\"EC\",\"addressLine1\":\"A\"}";
        var first = CompletableFuture.supplyAsync(() -> send(f.path(), "POST", body, "create"));
        var second = CompletableFuture.supplyAsync(() -> send(f.path(), "POST", body, "create"));
        var a = first.get(12, TimeUnit.SECONDS);
        var b = second.get(12, TimeUnit.SECONDS);
        assertEquals(201, a.status());
        assertEquals(201, b.status());
        assertEquals(a.body().path("data"), b.body().path("data"));
        var detail = f.path() + "/" + a.body().path("data").path("addressId").asText();
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
    void eachPageReadsOneCurrentSharedRevisionDuringConcurrentSiteMutations() throws Exception {
        var f = fixture();
        for (int i = 0; i < 10; i++)
            seedAddress(f, "PHYSICAL", false);
        var writer = CompletableFuture.runAsync(() -> {
            for (int version = 0; version < 10; version++) {
                var result = send(f.path().replace("/addresses", ""), "PATCH", "{\"name\":\"NAME " + version + "\"}",
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
