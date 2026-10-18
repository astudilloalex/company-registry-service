package com.alexastudillo.companyregistry.api.company;

import static org.junit.jupiter.api.Assertions.*;

import com.alexastudillo.companyregistry.contract.ReferenceStubServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(LifecycleHttpProfile.class)
class CompanyLifecycleConcurrencyHttpTest extends LifecycleHttpSupport {
    private CompletableFuture<Response> async(Supplier<Response> request) {
        return CompletableFuture.supplyAsync(request);
    }

    private Response done(CompletableFuture<Response> request) throws Exception {
        return request.get(10, TimeUnit.SECONDS);
    }

    private void status(Response response, int expected) {
        assertEquals(expected, response.statusCode(), response::asString);
    }

    /**
     * A real trigger holds the selected writer after it has acquired its aggregate
     * locks.
     */
    private final class WriteGate implements AutoCloseable {
        private final String name = "hu03_gate_" + UUID.randomUUID().toString().replace("-", "");
        private final long key = UUID.randomUUID().getMostSignificantBits();
        private final String table;
        private final Connection ddl;
        private final Connection holder;

        WriteGate(String table, String events) throws Exception {
            this(table, events, null);
        }

        WriteGate(String table, String events, String failActor) throws Exception {
            this.table = table;
            ddl = flyway.getConfiguration().getDataSource().getConnection();
            holder = flyway.getConfiguration().getDataSource().getConnection();
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, key);
                lock.execute();
            }
            try (var statement = ddl.createStatement()) {
                statement.execute("CREATE FUNCTION " + name
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"
                        + tenant + "'::uuid THEN PERFORM pg_advisory_xact_lock(" + key + "); "
                        + (failActor == null ? ""
                                : "IF NEW.created_by='" + failActor
                                        + "' THEN RAISE EXCEPTION 'injected HU03 rollback' USING ERRCODE='40001'; END IF; ")
                        + "END IF; RETURN NEW; END $$");
                statement.execute("CREATE TRIGGER " + name + " AFTER " + events + " ON " + table
                        + " FOR EACH ROW EXECUTE FUNCTION " + name + "()");
            }
        }

        void arrived() {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Integer.parseInt(scalar(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")) > 0);
        }

        void release() throws Exception {
            holder.rollback();
        }

        @Override
        public void close() throws Exception {
            holder.rollback();
            holder.close();
            try (var statement = ddl.createStatement()) {
                statement.execute("DROP TRIGGER " + name + " ON " + table);
                statement.execute("DROP FUNCTION " + name + "()");
            } finally {
                ddl.close();
            }
        }
    }

    private void waiting(int count) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Integer.parseInt(scalar(
                "SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype IN ('transactionid','tuple')")) >= count);
    }

    private List<Response> ordered(Parent parent, Supplier<Response> first, Supplier<Response> second)
            throws Exception {
        try (var holder = flyway.getConfiguration().getDataSource().getConnection();
                var statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE id='" + parent.id() + "' FOR UPDATE");
            var a = async(first);
            waiting(1);
            var b = async(second);
            waiting(2);
            holder.rollback();
            return List.of(done(a), done(b));
        }
    }

    private Response removeHeadquarters(String path, String action) {
        return action.equals("reclassify")
                ? request().header("If-Match", "1").body("{\"siteType\":\"BRANCH\"}").patch(path)
                : request().header("If-Match", "1").post(path + "/" + action);
    }

    @Test
    void realActivationAndEachIncompatibleHeadquartersWriterSerializeInBothOrders() throws Exception {
        for (String action : List.of("deactivate", "close", "reclassify")) {
            for (boolean companyFirst : new boolean[] { true, false }) {
                var parent = parent("DRAFT");
                String path = parent.path() + "/sites/" + headquarters(parent);
                try (var gate = new WriteGate(companyFirst ? "companies" : "company_sites", "UPDATE")) {
                    var first = async(() -> companyFirst ? command(parent, "activate", 0, null, null)
                            : removeHeadquarters(path, action));
                    gate.arrived();
                    var second = async(() -> companyFirst ? removeHeadquarters(path, action)
                            : command(parent, "activate", 0, null, null));
                    waiting(1);
                    gate.release();
                    status(done(first), 200);
                    var loser = done(second);
                    status(loser, 409);
                    assertEquals(companyFirst || !action.equals("close") && !action.equals("reclassify")
                            ? "active-headquarters-required"
                            : "legal-headquarters-required", loser.path("code"));
                }
                assertEquals("0", scalar("SELECT count(*) FROM companies c WHERE c.id='" + parent.id()
                        + "' AND c.status='ACTIVE' AND (SELECT count(*) FROM company_sites s WHERE s.tenant_id=c.tenant_id "
                        + "AND s.company_id=c.id AND s.site_type='HEADQUARTERS' AND s.status='ACTIVE')<>1"));
                assertEquals(companyFirst ? "1" : "0",
                        scalar("SELECT version FROM companies WHERE id='" + parent.id() + "'"));
            }
        }
    }

    private Response siteWriter(Parent parent, String path, String action, String type) {
        if (action.equals("register"))
            return request().header("Idempotency-Key", UUID.randomUUID())
                    .body("{\"code\":\"NEW\",\"name\":\"NEW\",\"siteType\":\"" + type + "\"}")
                    .post(parent.path() + "/sites");
        if (action.equals("patch") || action.equals("noop"))
            return request().header("If-Match", "0")
                    .body(action.equals("patch") ? "{\"name\":\"NEW\"}" : "{}").patch(path);
        return request().header("If-Match", "0").post(path + "/" + action);
    }

    @Test
    void realArchiveAndAllSiteWritersIncludingNoOpSerializeForEveryType() throws Exception {
        for (String type : List.of("HEADQUARTERS", "BRANCH", "WAREHOUSE", "OTHER")) {
            for (String action : List.of("register", "patch", "noop", "activate", "deactivate", "close")) {
                for (boolean archiveFirst : new boolean[] { true, false }) {
                    var parent = parent("DRAFT");
                    String id = action.equals("register") ? UUID.randomUUID().toString() : site(parent, type, "SITE");
                    String path = parent.path() + "/sites/" + id;
                    if (action.equals("deactivate")) {
                        // Fixture starts ACTIVE/version 0; both competing mutations still use real
                        // endpoints.
                        scalar("UPDATE company_sites SET status='ACTIVE' WHERE id='" + id + "' RETURNING id");
                    }
                    Supplier<Response> archive = () -> command(parent, "archive", 0, null, null);
                    Supplier<Response> site = () -> siteWriter(parent, path, action, type);
                    var results = ordered(parent, archiveFirst ? archive : site, archiveFirst ? site : archive);
                    status(results.getFirst(), !archiveFirst && action.equals("register") ? 201 : 200);
                    if (archiveFirst) {
                        status(results.getLast(), 409);
                        assertEquals(action.equals("register") ? "site-registration-not-allowed-for-archived-company"
                                : "site-mutation-not-allowed-for-archived-company", results.getLast().path("code"));
                    } else
                        status(results.getLast(), 200);
                    assertEquals("ARCHIVED:1",
                            scalar("SELECT status||':'||version FROM companies WHERE id='" + parent.id() + "'"));
                }
            }
        }
    }

    @Test
    void sameRevisionTransitionAndCompanyPatchRespectEffectiveChangesAndNoOps() throws Exception {
        for (String patch : List.of("{\"operatingName\":\"NEW\"}", "{}")) {
            for (boolean archiveFirst : new boolean[] { true, false }) {
                var parent = parent("DRAFT");
                Supplier<Response> archive = () -> command(parent, "archive", 0, null, null);
                Supplier<Response> update = () -> request().header("If-Match", "0").body(patch).patch(parent.path());
                var result = ordered(parent, archiveFirst ? archive : update, archiveFirst ? update : archive);
                status(result.getFirst(), 200);
                status(result.getLast(), !archiveFirst && patch.equals("{}") ? 200 : 412);
                assertEquals("1", scalar("SELECT version FROM companies WHERE id='" + parent.id() + "'"));
            }
        }
        var parent = parent("DRAFT");
        var result = ordered(parent, () -> command(parent, "archive", 0, null, null),
                () -> command(parent, "archive", 0, null, null));
        status(result.getFirst(), 200);
        status(result.getLast(), 412);
    }

    @Test
    void heldPartyKeepsCompanyAndSiteWritableAndForcesHeadquartersRevalidation() throws Exception {
        var parent = parent("DRAFT");
        String path = parent.path() + "/sites/" + headquarters(parent);
        var gate = LifecycleReferenceStubResource.stub.holdNext("/v1/parties/" + parent.party());
        var pending = async(() -> command(parent, "activate", 0, "held", null));
        assertTrue(gate.arrived().await(5, TimeUnit.SECONDS));
        try {
            status(request().header("If-Match", "0").body("{}").patch(parent.path()), 200);
            status(removeHeadquarters(path, "deactivate"), 200);
            assertEquals("0", scalar("SELECT version FROM companies WHERE id='" + parent.id() + "'"));
        } finally {
            gate.reply()
                    .complete(new ReferenceStubServer.Reply(200,
                            "{\"status\":200,\"code\":\"successful\",\"data\":{\"partyId\":\""
                                    + parent.party() + "\",\"type\":\"LEGAL_ENTITY\",\"recordStatus\":\"ACTIVE\"}}",
                            0));
        }
        var rejected = done(pending);
        status(rejected, 409);
        assertEquals("active-headquarters-required", rejected.path("code"));
    }

    @Test
    void concurrentAcceptancePrecedesRemoteFailureAndCurrentVersionOrState() throws Exception {
        var parent = parent("DRAFT");
        headquarters(parent);
        var gate = LifecycleReferenceStubResource.stub.holdNext("/v1/parties/" + parent.party());
        var pending = async(() -> command(parent, "activate", 0, "equivalent", null));
        assertTrue(gate.arrived().await(5, TimeUnit.SECONDS));
        var accepted = command(parent, "activate", 0, "equivalent", "{}");
        status(accepted, 200);
        gate.reply().complete(new ReferenceStubServer.Reply(503, "{}", 0));
        var replay = done(pending);
        status(replay, 200);
        assertEquals(accepted.jsonPath().getMap("data"), replay.jsonPath().getMap("data"));
        assertEquals("1", scalar("SELECT version FROM companies WHERE id='" + parent.id() + "'"));
    }

    @Test
    void equivalentDifferentAndCrossCompanyKeyRacesPreserveOneAcceptanceAndRollbackTheLoser() throws Exception {
        var parent = parent("DRAFT");
        var result = ordered(parent, () -> command(parent, "archive", 0, "same", null),
                () -> command(parent, "archive", 0, "same", "{}"));
        status(result.getFirst(), 200);
        status(result.getLast(), 200);
        assertEquals(result.getFirst().jsonPath().getMap("data"), result.getLast().jsonPath().getMap("data"));
        error(command(parent, "archive", 1, "same", null), 409, "idempotency-key-already-used-with-different-request");
        var first = parent("DRAFT");
        var second = parent("DRAFT");
        try (var gate = new WriteGate("company_idempotency_records", "INSERT")) {
            var a = async(() -> command(first, "archive", 0, "cross", null));
            gate.arrived();
            var b = async(() -> command(second, "archive", 0, "cross", null));
            waiting(1);
            gate.release();
            status(done(a), 200);
            var rejected = done(b);
            status(rejected, 409);
            assertEquals("idempotency-key-already-used-with-different-request", rejected.path("code"));
            assertEquals(2, rejected.jsonPath().getMap("").size());
        }
        assertEquals("DRAFT:0", scalar("SELECT status||':'||version FROM companies WHERE id='" + second.id() + "'"));
        assertEquals("0",
                scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + second.id() + "'"));
        assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                + "' AND idempotency_key='cross'"));
    }

    @Test
    void failingCompetitorRollsBackAndItsKeyCanBeAcceptedByAnotherCompany() throws Exception {
        var first = parent("DRAFT");
        var second = parent("DRAFT");
        try (var gate = new WriteGate("company_idempotency_records", "INSERT", "FailingActor")) {
            var a = async(() -> io.restassured.RestAssured.given().header("Tenant-Id", tenant)
                    .header("User-Id", "FailingActor")
                    .contentType("application/json")
                    .header("Process-Id", process).header("If-Match", "0").header("Idempotency-Key", "rollback")
                    .post(first.path() + "/archive"));
            gate.arrived();
            var b = async(() -> command(second, "archive", 0, "rollback", null));
            waiting(1);
            gate.release();
            var failed = done(a);
            assertTrue(failed.statusCode() == 503 || failed.statusCode() == 409, failed::asString);
            status(done(b), 200);
        }
        assertEquals("DRAFT:0", scalar("SELECT status||':'||version FROM companies WHERE id='" + first.id() + "'"));
        assertEquals("ARCHIVED:1", scalar("SELECT status||':'||version FROM companies WHERE id='" + second.id() + "'"));
        assertEquals("0", scalar("SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + first.id() + "'"));
        assertEquals("1", scalar("SELECT count(*) FROM company_idempotency_records WHERE tenant_id='" + tenant
                + "' AND idempotency_key='rollback'"));
    }

    @Test
    void currentRevisionPrecedesRemoteFailureAfterACompanyWriterCompletesDuringHttp() throws Exception {
        var parent = parent("DRAFT");
        headquarters(parent);
        var gate = LifecycleReferenceStubResource.stub.holdNext("/v1/parties/" + parent.party());
        var pending = async(() -> command(parent, "activate", 0, "held-version", null));
        assertTrue(gate.arrived().await(5, TimeUnit.SECONDS));
        try {
            status(request().header("If-Match", "0").body("{\"operatingName\":\"NEW\"}").patch(parent.path()), 200);
        } finally {
            gate.reply().complete(new ReferenceStubServer.Reply(503, "{}", 0));
        }
        var rejected = done(pending);
        status(rejected, 412);
        assertEquals("aggregate-version-mismatch", rejected.path("code"));
    }
}
