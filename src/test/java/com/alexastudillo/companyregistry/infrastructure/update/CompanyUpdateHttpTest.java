package com.alexastudillo.companyregistry.infrastructure.update;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator;
import com.fasterxml.jackson.databind.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class) @QuarkusTestResource(value=UpdateReferenceStubResource.class, restrictToAnnotatedClass=true)
@TestProfile(CompanyUpdateHttpTest.Profile.class)
class CompanyUpdateHttpTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {return Map.of("company-registry.outbox.mode","stored-only",
                "company-registry.update.wait-budget","2s", "quarkus.rest-client.party-registry.url","http://127.0.0.1:9", "quarkus.rest-client.geographic-reference.url","http://127.0.0.1:9");}
    }
    @Inject Flyway flyway;
    @Inject ObjectMapper json;
    UUID seed(UUID tenant,String code,String status) throws Exception {
        var id=new UuidV7Generator().next();
        sql("INSERT INTO companies(id,tenant_id,party_id,code,operating_name,description,logo_url,status,created_at,updated_at,created_by,updated_by) VALUES('"+id+"','"+tenant+"',uuidv7(),'"+code+"','COMPAÑÍA','DESCRIPTION','https://host/LogoAb.png','"+status+"','2026-10-17T10:00:00.123456Z','2026-10-17T10:00:00.123456Z','Creator-aB','Creator-aB')");
        return id;
    }
    void sql(String sql) throws Exception {try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement()){s.execute(sql);}}
    Map<String,String> headers(UUID tenant,long version,String key) {
        var h=new HashMap<String,String>();h.put("Tenant-Id",tenant.toString());h.put("Process-Id",UUID.randomUUID().toString());h.put("User-Id","Updater-aB");h.put("If-Match",Long.toString(version));if(key!=null)h.put("Idempotency-Key",key);return h;
    }
    io.restassured.response.Response patch(UUID tenant,UUID id,long version,String key,String body) {
        return given().headers(headers(tenant,version,key)).contentType("application/json").body(body).patch("/v1/companies/"+id);
    }
    long count(String table,UUID tenant) throws Exception {try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table+" WHERE tenant_id='"+tenant+"'")){r.next();return r.getLong(1);}}
    JsonNode data(io.restassured.response.Response response) throws Exception {return json.readTree(response.asString()).path("data");}
    @Test void effectiveClearNoopAndHistoricalReplayAreLocalAndPreserveOriginalAudit() throws Exception {
        var tenant=UUID.randomUUID();var id=seed(tenant,"CODE","ACTIVE");
        var accepted=patch(tenant,id,0,"K1","{\"operatingName\":\" compañía  ágil \",\"description\":null}");
        accepted.then().statusCode(200).body("code",equalTo("successful")).body("data.operatingName",equalTo("COMPAÑÍA  ÁGIL"))
                .body("data.version",equalTo(1)).body("data.status",equalTo("ACTIVE")).body("data.createdBy",equalTo("Creator-aB"))
                .body("data.updatedBy",equalTo("Updater-aB")).body("data.party",nullValue()).body("data.description",nullValue());
        assertFalse(data(accepted).has("party")); assertFalse(data(accepted).has("description"));
        var noop=patch(tenant,id,1,"NOOP","{}");noop.then().statusCode(200);assertEquals(data(accepted),data(noop));
        assertEquals(data(accepted),data(patch(tenant,id,1,null,"{\"operatingName\":\" COMPAÑÍA  ÁGIL \",\"description\":null}")));
        patch(tenant,id,1,null,"{\"logoUrl\":null}").then().statusCode(200).body("data.version",equalTo(2)).body("data.logoUrl",nullValue());
        var originalEvent=eventSnapshot(tenant);
        sql("UPDATE companies SET status='ARCHIVED',version=version+1,updated_by='Archiver',updated_at=updated_at+interval '1 second' WHERE id='"+id+"'");
        var h=headers(tenant,0,"K1");h.put("User-Id","Other-aB");
        var replay=given().headers(h).contentType("application/json").body("{\"description\":null,\"operatingName\":\"COMPAÑÍA  ÁGIL\"}").patch("/v1/companies/"+id);
        replay.then().statusCode(200).header("Process-Id",h.get("Process-Id"));assertEquals(data(accepted),data(replay));
        assertEquals(data(noop),data(patch(tenant,id,1,"NOOP","{}")));
        assertEquals(originalEvent,eventSnapshot(tenant));assertEquals(2,count("company_idempotency_records",tenant));assertEquals(2,count("company_outbox_events",tenant));
        patch(tenant,id,0,"K1","{}").then().statusCode(409);
        patch(tenant,id,0,"new","{}").then().statusCode(412);
        patch(tenant,id,3,"new","{}").then().statusCode(409);
    }
    String eventSnapshot(UUID tenant) throws Exception {try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT jsonb_agg(to_jsonb(e) ORDER BY aggregate_version)::text FROM company_outbox_events e WHERE tenant_id='"+tenant+"'")){r.next();return r.getString(1);}}
    @Test void allEditableStatesTenantIsolationAndCodeUniqueness() throws Exception {
        var tenant=UUID.randomUUID();
        for(var status:List.of("DRAFT","ACTIVE","INACTIVE")) {
            var id=seed(tenant,status,status);patch(tenant,id,0,null,"{\"code\":\" "+status.toLowerCase()+" \"}").then().statusCode(200).body("data.version",equalTo(0));
            patch(tenant,id,0,null,"{\"operatingName\":\"new\"}").then().statusCode(200).body("data.status",equalTo(status));
            patch(UUID.randomUUID(),id,1,null,"{}").then().statusCode(404).body("code",equalTo("not-found"));
        }
        var id=seed(tenant,"FREE","DRAFT");seed(tenant,"TAKEN","ARCHIVED");
        patch(tenant,id,0,"collision","{\"code\":\" taken \"}").then().statusCode(409);assertEquals(0,count("company_idempotency_records",tenant));
        var other=UUID.randomUUID();var otherId=seed(other,"FREE","DRAFT");patch(other,otherId,0,null,"{\"code\":\"TAKEN\"}").then().statusCode(200);
        patch(tenant,UUID.randomUUID(),0,null,"{}").then().statusCode(404);
    }
    @Test void strictBodyAndHeadersRejectBeforeWriting() throws Exception {
        var tenant=UUID.randomUUID();var id=seed(tenant,"STRICT","DRAFT");
        var invalid=new ArrayList<>(List.of("", "null", "[]", "1", "{", "{} {}", "{\"code\":\"A\",\"code\":\"B\"}","{\"code\":null}","{\"operatingName\":null}","{\"description\":\" \"}","{\"logoUrl\":\" https://host\"}"));
        for(var name:List.of("id","companyId","tenantId","partyId","createdAt","createdBy","status","version","updatedAt","updatedBy","unknown"))invalid.add("{\""+name+"\":null}");
        for(var name:List.of("code","operatingName","description","logoUrl"))for(var value:List.of("1","true","{}","[]"))invalid.add("{\""+name+"\":"+value+"}");
        for(var body:invalid)patch(tenant,id,0,"invalid",body).then().statusCode(400);
        for(var version:List.of("\"0\"","*","01","-1","+1","0 1","9223372036854775808","0,0")) {
            var h=headers(tenant,0,null);h.put("If-Match",version);var response=given().headers(h).contentType("application/json").body("{}").patch("/v1/companies/"+id);assertEquals(400,response.statusCode(),"If-Match=["+version+"]: "+response.asString());
        }
        for(var field:List.of("Tenant-Id","User-Id","Process-Id","If-Match")) {
            var h=headers(tenant,0,null);h.remove(field);given().headers(h).contentType("application/json").body("{}").patch("/v1/companies/"+id).then().statusCode(400);
            given().headers(headers(tenant,0,null)).header(field,"duplicate").contentType("application/json").body("{}").patch("/v1/companies/"+id).then().statusCode(400);
        }
        for(var key:List.of("", " ","x".repeat(129)))patch(tenant,id,0,key,"{}").then().statusCode(400);
        given().headers(headers(tenant,0,"k")).header("Idempotency-Key","other").contentType("application/json").body("{}").patch("/v1/companies/"+id).then().statusCode(400);
        given().headers(headers(tenant,0,null)).contentType("text/plain").body("{}").patch("/v1/companies/"+id).then().statusCode(415);
        given().headers(headers(tenant,0,null)).contentType("application/json").body("{}").patch("/v1/companies/broken").then().statusCode(400);
        patch(tenant,id,Long.MAX_VALUE,null,"{}").then().statusCode(412);
        assertEquals(0,count("company_idempotency_records",tenant));assertEquals(0,count("company_outbox_events",tenant));
    }
    long blocked() throws Exception {try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted AND relation='companies'::regclass")){r.next();return r.getLong(1);}}
    @Test void concurrentEffectiveChangesAndKeyRacesHaveOneAcceptance() throws Exception {
        for(var sameKey:List.of(false,true)) {
            var tenant=UUID.randomUUID();var id=seed(tenant,"RACE","DRAFT");
            try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                c.setAutoCommit(false);s.execute("LOCK TABLE companies IN EXCLUSIVE MODE");
                var a=pool.submit(()->patch(tenant,id,0,"A","{\"operatingName\":\"new\"}"));
                var b=pool.submit(()->patch(tenant,id,0,sameKey?"A":"B","{\"operatingName\":\"new\"}"));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1)).until(()->blocked()>=2);c.rollback();
                var ra=a.get(5,TimeUnit.SECONDS);var rb=b.get(5,TimeUnit.SECONDS);
                if(sameKey) {assertEquals(200,ra.statusCode());assertEquals(data(ra),data(rb));}
                else assertEquals(Set.of(200,412),Set.of(ra.statusCode(),rb.statusCode()));
                assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
            }
        }
    }
    @Test void differentCompaniesCompeteForOneCodeOrCompletionKey() throws Exception {
        for(var sameKey:List.of(false,true)) {
            var tenant=UUID.randomUUID();var one=seed(tenant,"ONE","DRAFT");var two=seed(tenant,"TWO","DRAFT");
            try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                c.setAutoCommit(false);s.execute("LOCK TABLE companies IN EXCLUSIVE MODE");
                var a=pool.submit(()->patch(tenant,one,0,"A",sameKey?"{\"operatingName\":\"NEW\"}":"{\"code\":\"SHARED\"}"));
                var b=pool.submit(()->patch(tenant,two,0,sameKey?"A":"B",sameKey?"{\"operatingName\":\"NEW\"}":"{\"code\":\" shared \"}"));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1)).until(()->blocked()>=2);c.rollback();
                assertEquals(Set.of(200,409),Set.of(a.get(5,TimeUnit.SECONDS).statusCode(),b.get(5,TimeUnit.SECONDS).statusCode()));
                assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
            }
        }
    }
    @Test void lockTimeoutLeavesNoEffectsAndReleasesForRetry() throws Exception {
        var tenant=UUID.randomUUID();var id=seed(tenant,"TIMEOUT","DRAFT");
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement()) {
            c.setAutoCommit(false);s.execute("SELECT id FROM companies WHERE id='"+id+"' FOR UPDATE");
            patch(tenant,id,0,"wait","{\"operatingName\":\"NEW\"}").then().statusCode(503);c.rollback();
        }
        assertEquals(0,count("company_idempotency_records",tenant));assertEquals(0,count("company_outbox_events",tenant));
        patch(tenant,id,0,"wait","{\"operatingName\":\"NEW\"}").then().statusCode(200).body("data.version",equalTo(1));
    }
    @Test void noopKeepsPhysicalRowAndCannotAcceptAnObsoleteProtectedVersion() throws Exception {
        var tenant=UUID.randomUUID();var id=seed(tenant,"NOOP","DRAFT");
        String before;
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT xmin::text FROM companies WHERE id='"+id+"'")){r.next();before=r.getString(1);}
        patch(tenant,id,0,"noop","{}").then().statusCode(200);
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT xmin::text FROM companies WHERE id='"+id+"'")){r.next();assertEquals(before,r.getString(1));}
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            c.setAutoCommit(false);s.execute("SELECT id FROM companies WHERE id='"+id+"' FOR UPDATE");
            var pending=pool.submit(()->patch(tenant,id,0,"blocked-noop","{}"));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1)).until(()->{
                try(var other=flyway.getConfiguration().getDataSource().getConnection();var st=other.createStatement();var r=st.executeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype='transactionid'")){r.next();return r.getLong(1)>0;}
            });
            s.execute("UPDATE companies SET operating_name='HOLDER',version=version+1 WHERE id='"+id+"'");c.commit();
            assertEquals(412,pending.get(5,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(1,count("company_idempotency_records",tenant));assertEquals(0,count("company_outbox_events",tenant));
    }

    @Test void acceptsProtocolWhitespaceAroundTheExtractedExpectedVersion() throws Exception {
        var tenant=UUID.randomUUID();var id=seed(tenant,"WHITESPACE","DRAFT");
        try(var socket=new java.net.Socket("127.0.0.1",io.restassured.RestAssured.port)) {
            socket.setSoTimeout(5000);
            var raw="PATCH /v1/companies/"+id+" HTTP/1.1\r\nHost: localhost\r\nTenant-Id: "+tenant
                    +"\r\nProcess-Id: "+UUID.randomUUID()+"\r\nUser-Id: Updater-aB\r\nIf-Match:  0 \r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            socket.getOutputStream().write(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response=new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200"),response);
        }
    }
    @Test void changeNoopAndReplayNeverCallUnusableReferenceProviders() throws Exception {
        var stub=UpdateReferenceStubResource.stub;stub.reset();
        var tenant=UUID.randomUUID();var id=seed(tenant,"LOCAL","DRAFT");
        patch(tenant,id,0,"local","{\"operatingName\":\"NEW\"}").then().statusCode(200);
        patch(tenant,id,1,"noop","{}").then().statusCode(200);
        patch(tenant,id,0,"local","{\"operatingName\":\"NEW\"}").then().statusCode(200);
        assertTrue(stub.requests().isEmpty());
    }
    @Test void corruptHistoricalPoliciesKeysAndSnapshotsAreSanitizedAndNeverReapplied() throws Exception {
        for(var mutation:List.of("fingerprint_version=2", "fingerprint_key_id='unknown-key'", "result_snapshot_schema_version=2", "result_snapshot='{\"secret\":\"DO-NOT-EXPOSE\"}'::jsonb")) {
            var tenant=UUID.randomUUID();var id=seed(tenant,"CORRUPT","DRAFT");var body="{\"operatingName\":\"NEW\"}";
            patch(tenant,id,0,"key",body).then().statusCode(200);
            sql("UPDATE company_idempotency_records SET "+mutation+" WHERE tenant_id='"+tenant+"'");
            var response=patch(tenant,id,0,"key",body);response.then().statusCode(500).body("code",equalTo("server-error"));
            assertFalse(response.asString().contains("DO-NOT-EXPOSE"));assertFalse(response.asString().contains("historical"));
            assertEquals(1,count("company_idempotency_records",tenant));assertEquals(1,count("company_outbox_events",tenant));
            patch(tenant,id,1,null,"{}").then().statusCode(200).body("data.version",equalTo(1));
        }
    }

}
