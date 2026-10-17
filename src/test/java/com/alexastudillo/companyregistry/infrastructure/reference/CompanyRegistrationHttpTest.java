package com.alexastudillo.companyregistry.infrastructure.reference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import com.fasterxml.jackson.databind.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class) @QuarkusTestResource(ReferenceStubResource.class)
@TestProfile(CompanyRegistrationHttpTest.Profile.class)
class CompanyRegistrationHttpTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Map.of("company-registry.outbox.mode","stored-only","company-registry.registration.wait-budget","5s"); }
    }
    @Inject Flyway flyway;
    @Inject ObjectMapper json;
    private Map<String,String> headers(UUID tenant) { return Map.of("Tenant-Id",tenant.toString(),"Process-Id",UUID.randomUUID().toString(),"User-Id","Actor-aB","Idempotency-Key","Key-aB"); }
    private String body(UUID party,String code) { return "{\"partyId\":\""+party+"\",\"code\":\""+code+"\",\"operatingName\":\" compañía  ágil \",\"logoUrl\":\"https://example.com/Logo.png\"}"; }
    private void provider(UUID party) { var stub=ReferenceStubResource.stub; stub.reply("/v1/parties/"+party,200,PartyReadAdapterTest.detail(party,"LEGAL_ENTITY","ACTIVE")); stub.reply("/v1/parties/"+party+"/identifiers",200,PartyReadAdapterTest.page(PartyReadAdapterTest.identifier(party,UUID.randomUUID(),"VERIFIED"),1,null)); }
    private long count(String table,UUID tenant) throws Exception {
        try(var connection=flyway.getConfiguration().getDataSource().getConnection(); var statement=connection.prepareStatement("SELECT count(*) FROM "+table+" WHERE tenant_id=?")) { statement.setObject(1,tenant);try(var rows=statement.executeQuery()){assertTrue(rows.next());return rows.getLong(1);} }
    }
    private long blockedInserts() throws Exception {
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_locks WHERE relation='companies'::regclass AND mode='RowExclusiveLock' AND NOT granted")) {r.next();return r.getLong(1);}
    }
    @Test void clearedDescriptionIsOmittedByPatchAndTheSubsequentEnrichedGet() throws Exception {
        var stub=ReferenceStubResource.stub;stub.reset();
        var tenant=UUID.randomUUID();var party=UUID.randomUUID();provider(party);var h=headers(tenant);
        var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body(party,"clear"));
        request.put("description","original description");
        var created=given().headers(h).contentType("application/json").body(request.toString()).post("/v1/companies");
        created.then().statusCode(201).body("data.description",equalTo("ORIGINAL DESCRIPTION"));
        var id=json.readTree(created.asString()).path("data").path("companyId").asText();
        var update=new HashMap<>(h);update.put("If-Match","0");update.put("Idempotency-Key","clear-description");
        update.put("User-Id","Updater-aB");update.put("Process-Id",UUID.randomUUID().toString());
        stub.reset();provider(party);
        var patched=given().headers(update).contentType("application/json")
                .body("{\"description\":null,\"operatingName\":\" updated company \"}").patch("/v1/companies/"+id);
        patched.then().statusCode(200).body("data.operatingName",equalTo("UPDATED COMPANY")).body("data.version",equalTo(1));
        var operational=json.readTree(patched.asString()).path("data");
        assertFalse(operational.has("description"));assertFalse(operational.has("party"));assertTrue(stub.requests().isEmpty());
        var fetched=given().headers(h).get("/v1/companies/"+id);
        fetched.then().statusCode(200).body("data.party.partyId",equalTo(party.toString()));
        var enriched=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(fetched.asString()).path("data");
        assertFalse(enriched.has("description"));assertTrue(enriched.path("party").isObject());
        enriched.remove("party");assertEquals(operational,enriched);
        assertFalse(stub.requests().isEmpty());
        assertEquals(2,count("company_idempotency_records",tenant));assertEquals(2,count("company_outbox_events",tenant));
    }
    @Test void creationAndUpdateKeysRemainIndependentAcrossOperationsAndTenants() throws Exception {
        var stub=ReferenceStubResource.stub;stub.reset();
        var tenants=List.of(UUID.randomUUID(),UUID.randomUUID());
        var parties=List.of(UUID.randomUUID(),UUID.randomUUID());
        var creations=new ArrayList<JsonNode>();var updates=new ArrayList<JsonNode>();
        for(int i=0;i<tenants.size();i++) {
            var tenant=tenants.get(i);var party=parties.get(i);provider(party);var h=headers(tenant);
            var created=given().headers(h).contentType("application/json").body(body(party,"scoped")).post("/v1/companies");
            created.then().statusCode(201);
            var original=json.readTree(created.asString()).path("data");creations.add(original);
            var update=new HashMap<>(h);update.put("If-Match","0");update.put("User-Id","Updater-aB");
            var patched=given().headers(update).contentType("application/json")
                    .body("{\"operatingName\":\"updated tenant "+i+"\"}").patch("/v1/companies/"+original.path("companyId").asText());
            patched.then().statusCode(200).body("data.version",equalTo(1)).body("data.operatingName",equalTo("UPDATED TENANT "+i));
            updates.add(json.readTree(patched.asString()).path("data"));
        }
        assertNotEquals(creations.get(0).path("companyId"),creations.get(1).path("companyId"));
        stub.reset();
        for(int i=0;i<tenants.size();i++) {
            var tenant=tenants.get(i);var retry=new HashMap<>(headers(tenant));
            retry.put("User-Id","Replay-actor");
            var createdReplay=given().headers(retry).contentType("application/json").body(body(parties.get(i),"scoped")).post("/v1/companies");
            createdReplay.then().statusCode(201).header("Process-Id",retry.get("Process-Id"));
            assertEquals(creations.get(i),json.readTree(createdReplay.asString()).path("data"));
            retry.put("If-Match","0");
            var updatedReplay=given().headers(retry).contentType("application/json")
                    .body("{\"operatingName\":\"updated tenant "+i+"\"}").patch("/v1/companies/"+creations.get(i).path("companyId").asText());
            updatedReplay.then().statusCode(200).header("Process-Id",retry.get("Process-Id"));
            assertEquals(updates.get(i),json.readTree(updatedReplay.asString()).path("data"));
            try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.prepareStatement(
                    "SELECT operation,idempotency_key FROM company_idempotency_records WHERE tenant_id=? ORDER BY operation")) {
                s.setObject(1,tenant);
                try(var rows=s.executeQuery()) {
                    for(var operation:List.of("company.create.v1","company.update.v1")) {
                        assertTrue(rows.next());assertEquals(operation,rows.getString(1));assertEquals("Key-aB",rows.getString(2));
                    }
                    assertFalse(rows.next());
                }
            }
            assertEquals(1,count("companies",tenant));assertEquals(2,count("company_outbox_events",tenant));
        }
        assertTrue(stub.requests().isEmpty());
    }
    @Test void statusFilteredForwardPageUsesTheExistingTenantOrderIndex() throws Exception {
        var tenant=UUID.randomUUID();
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement()) {
            try {
                s.executeUpdate("INSERT INTO companies(tenant_id,party_id,code,operating_name,status,created_by,updated_by) SELECT '"+tenant+"'::uuid,uuidv7(),'PLAN'||n,'PLAN',CASE WHEN n%10=0 THEN 'ARCHIVED' ELSE 'DRAFT' END::company_record_status,'plan','plan' FROM generate_series(1,10000) n");
                s.execute("ANALYZE companies");var plan=new StringBuilder();
                try(var rows=s.executeQuery("EXPLAIN (ANALYZE,BUFFERS) SELECT * FROM companies WHERE tenant_id='"+tenant+"' AND status='DRAFT' ORDER BY created_at DESC,id DESC LIMIT 51")) {while(rows.next())plan.append(rows.getString(1)).append('\n');}
                var directory=java.nio.file.Path.of("build/reports/hu01");java.nio.file.Files.createDirectories(directory);java.nio.file.Files.writeString(directory.resolve("status-page-explain.txt"),plan.toString());
                assertTrue(plan.toString().contains("Index Scan"),plan.toString());
            } finally {s.executeUpdate("DELETE FROM companies WHERE tenant_id='"+tenant+"'");}
        }
    }
    @Test void readsCurrentStateWithAndFiltersKeysetPaginationAndTenantIsolation() throws Exception {
        var stub=ReferenceStubResource.stub;stub.reset();var tenant=UUID.randomUUID();var other=UUID.randomUUID();var h=new HashMap<>(headers(tenant));h.remove("Idempotency-Key");var ids=new ArrayList<UUID>();var parties=new ArrayList<UUID>();
        for(int i=0;i<3;i++) {
            var party=UUID.randomUUID();var id=UUID.fromString("0199e000-0000-7000-8000-00000000000"+(i+1));ids.add(id);parties.add(party);provider(party);
            try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.prepareStatement("INSERT INTO companies(id,tenant_id,party_id,code,operating_name,status,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,'NAME',?::company_record_status,'2026-10-17T14:30:00.123456Z','2026-10-17T14:30:00.123456Z','Original-actor','Original-actor')")) {
                s.setObject(1,id);s.setObject(2,tenant);s.setObject(3,party);s.setString(4,"CODE"+i);s.setString(5,i==0?"ARCHIVED":"DRAFT");s.executeUpdate();
            }
        }
        var first=given().headers(h).queryParam("limit",1).get("/v1/companies").then().statusCode(200).body("numberOfElements",equalTo(1))
                .body("data[0].companyId",equalTo(ids.getLast().toString())).body("pagination",nullValue()).body("totalElements",nullValue()).extract().asString();
        var cursor=json.readTree(first).path("nextCursor").asText();assertFalse(cursor.isBlank());
        given().headers(h).queryParam("limit",2).queryParam("cursor",cursor).get("/v1/companies").then().statusCode(200)
                .body("data.companyId",equalTo(List.of(ids.get(1).toString(),ids.getFirst().toString()))).body("numberOfElements",equalTo(2)).body("nextCursor",nullValue());
        given().headers(h).queryParam("code"," code0 ").queryParam("partyId",parties.getFirst()).queryParam("status","ARCHIVED").get("/v1/companies").then().statusCode(200).body("numberOfElements",equalTo(1));
        given().headers(h).queryParam("code","CODE0").queryParam("status","ACTIVE").get("/v1/companies").then().statusCode(200).body("data",empty());
        stub.reply("/v1/parties/"+parties.getFirst(),200,PartyReadAdapterTest.detail(parties.getFirst(),"LEGAL_ENTITY","ARCHIVED"));
        given().headers(h).get("/v1/companies/"+ids.getFirst()).then().statusCode(200).body("data.party.recordStatus",equalTo("ARCHIVED")).body("data.createdBy",equalTo("Original-actor"));
        stub.reset();given().headers(headers(other)).get("/v1/companies/"+ids.getFirst()).then().statusCode(404).body("code",equalTo("not-found"));
        given().headers(h).get("/v1/companies/"+UUID.randomUUID()).then().statusCode(404).body("code",equalTo("not-found"));
        given().headers(headers(other)).get("/v1/companies").then().statusCode(200).body("data",empty());assertTrue(stub.requests().isEmpty());
        for(var query:List.of("unknown=1","limit=0","limit=201","limit=1&limit=2","status=unknown","partyId=broken","cursor=broken","code=")) {
            given().headers(h).get("/v1/companies?"+query).then().statusCode(400);
        }
        given().headers(h).get("/v1/companies/not-a-uuid").then().statusCode(400);
        given().headers(headers(other)).queryParam("cursor",cursor).get("/v1/companies").then().statusCode(400);
        assertTrue(stub.requests().isEmpty());assertEquals(0,count("company_outbox_events",tenant));
        given().headers(h).get("/v1/companies/"+ids.getFirst()).then().statusCode(502).body("code",equalTo("dependency-response-invalid"));
        for(var party:parties) stub.reply("/v1/parties/"+party,503,"{}");
        given().headers(h).get("/v1/companies/"+ids.getFirst()).then().statusCode(503).body("code",equalTo("dependency-unavailable"));
        given().headers(h).get("/v1/companies").then().statusCode(503).body("code",equalTo("dependency-unavailable")).body("data",nullValue());
        assertEquals(0,count("company_outbox_events",tenant));assertEquals(0,count("company_idempotency_records",tenant));
    }
    @Test void racesResolveAfterSqlUnicityAndLeaveExactlyOneAcceptance() throws Exception {
        for(var scenario:List.of("equivalent","different-content","party-conflict","code-conflict")) {
            var stub=ReferenceStubResource.stub;stub.reset();var tenant=UUID.randomUUID();var party=UUID.randomUUID();
            var otherParty=scenario.equals("equivalent")||scenario.equals("party-conflict")?party:UUID.randomUUID();
            provider(party);provider(otherParty);var first=headers(tenant);var second=new HashMap<>(headers(tenant));
            if(scenario.endsWith("conflict")) second.put("Idempotency-Key","Another-key");
            var code=scenario.equals("equivalent")?" ACME ":"acme";var otherCode=scenario.equals("party-conflict")||scenario.equals("different-content")?"other":"acme";
            try(var holder=flyway.getConfiguration().getDataSource().getConnection();var statement=holder.createStatement();var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                holder.setAutoCommit(false);statement.execute("LOCK TABLE companies IN SHARE MODE");
                var a=executor.submit(()->given().headers(first).contentType("application/json").body(body(party,code)).post("/v1/companies"));
                var b=executor.submit(()->given().headers(second).contentType("application/json").body(body(otherParty,otherCode)).post("/v1/companies"));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4)).until(()->blockedInserts()>=2);
                holder.rollback();var left=a.get(10,java.util.concurrent.TimeUnit.SECONDS);var right=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
                if(scenario.equals("equivalent")) {assertEquals(201,left.statusCode());assertEquals(201,right.statusCode());assertEquals(json.readTree(left.asString()).path("data"),json.readTree(right.asString()).path("data"));}
                else {assertEquals(Set.of(201,409),Set.of(left.statusCode(),right.statusCode()));}
            }
            assertEquals(1,count("companies",tenant));assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
        }
    }
    @Test void lockTimeoutRollsBackClosesTheSessionAndAllowsRetry() throws Exception {
        var stub=ReferenceStubResource.stub;stub.reset();var tenant=UUID.randomUUID();var party=UUID.randomUUID();provider(party);var h=headers(tenant);
        try(var holder=flyway.getConfiguration().getDataSource().getConnection();var statement=holder.createStatement();var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);statement.execute("LOCK TABLE companies IN SHARE MODE");
            var request=executor.submit(()->given().headers(h).contentType("application/json").body(body(party,"timeout")).post("/v1/companies"));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4)).until(()->blockedInserts()>=1);
            var response=request.get(8,java.util.concurrent.TimeUnit.SECONDS);assertEquals(503,response.statusCode());assertEquals("dependency-unavailable",response.jsonPath().getString("code"));
            assertEquals(0,blockedInserts());holder.rollback();
        }
        assertEquals(0,count("companies",tenant));assertEquals(0,count("company_idempotency_records",tenant));assertEquals(0,count("company_outbox_events",tenant));
        given().headers(h).contentType("application/json").body(body(party,"timeout")).post("/v1/companies").then().statusCode(201);
    }
    @Test void createsCanonicalDraftAndReplaysCompleteOriginalSnapshotWithoutProvidersOrWrites() throws Exception {
        var stub=ReferenceStubResource.stub;stub.reset();var tenant=UUID.randomUUID();var party=UUID.randomUUID();provider(party);var h=headers(tenant);
        var response=given().headers(h).contentType("application/json").body(body(party," acme ")).post("/v1/companies").then().statusCode(201)
                .header("Process-Id",equalTo(h.get("Process-Id"))).body("code",equalTo("successful")).body("data.status",equalTo("DRAFT"))
                .body("data.version",equalTo(0)).body("data.code",equalTo("ACME")).body("data.operatingName",equalTo("COMPAÑÍA  ÁGIL"))
                .body("data.createdBy",equalTo("Actor-aB")).body("data.party.identifiers[0].isPrimary",equalTo(true)).extract().asString();
        JsonNode original=json.readTree(response).path("data"); assertEquals(7,UUID.fromString(original.path("companyId").asText()).version());
        assertEquals(original.path("createdAt"),original.path("updatedAt"));
        assertEquals(1,count("companies",tenant));assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
        try(var connection=flyway.getConfiguration().getDataSource().getConnection();var statement=connection.prepareStatement("UPDATE companies SET operating_name='CHANGED', updated_by='Later-actor',version=1 WHERE tenant_id=?")) {statement.setObject(1,tenant);assertEquals(1,statement.executeUpdate());}
        stub.reset();var retry=new HashMap<>(h);retry.put("Process-Id",UUID.randomUUID().toString());retry.put("User-Id","Another-actor");
        var replay=given().headers(retry).contentType("application/json").body(body(party,"ACME")).post("/v1/companies").then().statusCode(201)
                .header("Process-Id",equalTo(retry.get("Process-Id"))).extract().asString();
        assertEquals(original,json.readTree(replay).path("data"));assertTrue(stub.requests().isEmpty());
        given().headers(retry).contentType("application/json").body(body(party,"different")).post("/v1/companies").then().statusCode(409).body("code",equalTo("resource-conflict"));
        assertTrue(stub.requests().isEmpty());assertEquals(1,count("companies",tenant));assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.prepareStatement("UPDATE company_idempotency_records SET result_snapshot='{}' WHERE tenant_id=?")) {s.setObject(1,tenant);s.executeUpdate();}
        given().headers(retry).contentType("application/json").body(body(party,"ACME")).post("/v1/companies").then().statusCode(500).body("code",equalTo("server-error"));
        assertTrue(stub.requests().isEmpty());
    }
    @Test void mandatoryWriteFailuresRollbackEverythingAndDoNotConsumeTheKey() throws Exception {
        for(var table:List.of("companies","company_idempotency_records","company_outbox_events")) {
            var stub=ReferenceStubResource.stub;stub.reset();var tenant=UUID.randomUUID();var party=UUID.randomUUID();provider(party);var h=headers(tenant);
            var function="hu01_fail_"+UUID.randomUUID().toString().replace("-","");var trigger=function+"_trigger";
            try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement()) {
                s.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"'::uuid THEN RAISE EXCEPTION 'mandatory-write-test' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
                s.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+function+"()");
                try {
                    given().headers(h).contentType("application/json").body(body(party,"failure")).post("/v1/companies").then().statusCode(500).body("code",equalTo("server-error")).body(not(containsString("mandatory-write-test")));
                    assertEquals(0,count("companies",tenant));assertEquals(0,count("company_idempotency_records",tenant));assertEquals(0,count("company_outbox_events",tenant));
                } finally {s.execute("DROP TRIGGER "+trigger+" ON "+table);s.execute("DROP FUNCTION "+function+"()");}
            }
            given().headers(h).contentType("application/json").body(body(party,"failure")).post("/v1/companies").then().statusCode(201);
            assertEquals(1,count("companies",tenant));assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
        }
    }
    @Test void unknownOutboxDuplicateIsSanitized500AndNeverABusinessConflict() throws Exception {
        var stub=ReferenceStubResource.stub;stub.reset();var tenant=UUID.randomUUID();var party=UUID.randomUUID();provider(party);var h=headers(tenant);
        given().headers(h).contentType("application/json").body(body(party,"first")).post("/v1/companies").then().statusCode(201);
        var next=UUID.randomUUID();provider(next);var second=new HashMap<>(h);second.put("Idempotency-Key","second");
        var function="hu01_duplicate_"+UUID.randomUUID().toString().replace("-","");
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement()) {
            UUID event;try(var r=s.executeQuery("SELECT id FROM company_outbox_events WHERE tenant_id='"+tenant+"'")){r.next();event=r.getObject(1,UUID.class);}
            s.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"'::uuid THEN NEW.id='"+event+"'::uuid; END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON company_outbox_events FOR EACH ROW EXECUTE FUNCTION "+function+"()");
            try {given().headers(second).contentType("application/json").body(body(next,"second")).post("/v1/companies").then().statusCode(500).body("code",equalTo("server-error"));}
            finally {s.execute("DROP TRIGGER "+function+" ON company_outbox_events");s.execute("DROP FUNCTION "+function+"()");}
        }
        assertEquals(1,count("companies",tenant));assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
    }
    @Test void rejectsReferencesAndIncompleteEnrichmentBeforeWriting() throws Exception {
        var stub=ReferenceStubResource.stub;var tenant=UUID.randomUUID();var party=UUID.randomUUID();var h=headers(tenant);var path="/v1/parties/"+party;
        stub.reset();given().headers(h).contentType("application/json").body(body(party,"acme")).post("/v1/companies").then().statusCode(404).body("code",equalTo("reference-not-found"));
        for(var type:List.of("NATURAL_PERSON","LEGAL_ENTITY")) {
            stub.reply(path,200,PartyReadAdapterTest.detail(party,type,type.equals("LEGAL_ENTITY")?"ARCHIVED":"ACTIVE"));
            given().headers(h).contentType("application/json").body(body(party,"acme")).post("/v1/companies").then().statusCode(400).body("code",equalTo("reference-invalid"));
        }
        provider(party);stub.reply(path+"/identifiers",503,"{}");
        given().headers(h).contentType("application/json").body(body(party,"acme")).post("/v1/companies").then().statusCode(503).body("code",equalTo("dependency-unavailable"));
        stub.reply(path+"/identifiers",200,"{}");
        given().headers(h).contentType("application/json").body(body(party,"acme")).post("/v1/companies").then().statusCode(502).body("code",equalTo("dependency-response-invalid"));
        assertEquals(0,count("companies",tenant));assertEquals(0,count("company_idempotency_records",tenant));assertEquals(0,count("company_outbox_events",tenant));
    }
    @Test void closedInputAndKeyValidationNeverInvokeTheProvider() throws Exception {
        var stub=ReferenceStubResource.stub;stub.reset();var tenant=UUID.randomUUID();var h=headers(tenant);var party=UUID.randomUUID();
        for(var input:List.of(body(party,"acme").replace("}",",\"status\":\"ACTIVE\"}"),"{\"code\":123}",body(party," "))) {
            given().headers(h).contentType("application/json").body(input).post("/v1/companies").then().statusCode(400);
        }
        var missing=new HashMap<>(h);missing.remove("Idempotency-Key");
        given().headers(missing).contentType("application/json").body(body(party,"acme")).post("/v1/companies").then().statusCode(400);
        given().headers(h).header("Idempotency-Key","another").contentType("application/json").body(body(party,"acme")).post("/v1/companies").then().statusCode(400);
        assertTrue(stub.requests().isEmpty());assertEquals(0,count("companies",tenant));
    }
}
