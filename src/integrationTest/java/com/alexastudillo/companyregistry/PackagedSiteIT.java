package com.alexastudillo.companyregistry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real productive JVM/native process, preserved database, no in-process service mocks. */
@QuarkusIntegrationTest @QuarkusTestResource(ProcessEnvironmentResource.class)
class PackagedSiteIT {
    private final ObjectMapper json=new ObjectMapper();
    @io.quarkus.test.common.http.TestHTTPResource java.net.URI runningBase;
    private Connection db() throws Exception {return DriverManager.getConnection(ProcessEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),"company_registry_migration","migration_test");}
    private UUID seed(UUID tenant) throws Exception {
        try(var c=db();var s=c.createStatement();var r=s.executeQuery("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"
                +tenant+"',uuidv7(),'"+UUID.randomUUID().toString().toUpperCase()+"','PARENT','Creator','Creator') RETURNING id")){r.next();return r.getObject(1,UUID.class);}
    }
    private Map<String,String> headers(UUID tenant,String key) {
        var h=new HashMap<String,String>();h.put("Tenant-Id",tenant.toString());h.put("User-Id","Actor-aB");h.put("Process-Id",UUID.randomUUID().toString());if(key!=null)h.put("Idempotency-Key",key);return h;
    }
    private String path(UUID company){return "/v1/companies/"+company+"/sites";}
    private HttpResponse<String> request(HttpClient client,String base,String path,String method,String body,Map<String,String> headers) throws Exception {
        var r=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));headers.forEach(r::header);
        if(body!=null)r.header("Content-Type","application/json");
        return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void durableSiteReplaySurvivesMutationClosureArchivalRestartRotationAndModeChanges(@TempDir Path work) throws Exception {
        ProcessEnvironmentResource.stub.reset();var tenant=UUID.randomUUID();var company=seed(tenant);
        MutationFixture mutationFixture;
        try (var client = HttpClient.newHttpClient()) {
            mutationFixture = mutationFixture(client, runningBase.toString().replaceAll("/$", ""), "stored-only");
        }
        var body="{\"code\":\" ß \",\"name\":\" compañía  ágil \"}";
        var original = assertOriginalSite(tenant, company, body);
        var site=original.path("siteId").asText();
        try(var c=db();var s=c.createStatement()) {
            s.execute("UPDATE company_sites SET name='LATER',status='ACTIVE',version=1,updated_by='Later' WHERE id='"+site+"'");
            s.execute("UPDATE companies SET status='ACTIVE' WHERE id='"+company+"'");
        }
        given().headers(headers(tenant,null)).get(path(company)+"/"+site).then().statusCode(200)
                .body("data.effectivelyOperational",org.hamcrest.Matchers.equalTo(true));
        try(var c=db();var s=c.createStatement()) {
            s.execute("UPDATE company_sites SET status='CLOSED',version=2 WHERE id='"+site+"'");
            s.execute("UPDATE companies SET status='ARCHIVED',version=1 WHERE id='"+company+"'");
        }
        for(var mode:List.of("disabled","stored-only","published")) {
            assertModeLifecycle(work, mode, mutationFixture, tenant, company, site, body, original);
        }
        assertTrue(ProcessEnvironmentResource.stub.requests().isEmpty());assertEvent(tenant,original);
    }
    @Test void noOpReplayPreservesSiteAuditDespiteClockSkew() throws Exception {
        var tenant = UUID.randomUUID();
        var company = seed(tenant);
        var site = given().headers(headers(tenant, "create")).contentType("application/json")
                .body("{\"code\":\"SITE\",\"name\":\"SITE\"}").post(path(company))
                .then().statusCode(201).extract().path("data.siteId").toString();
        try (var connection = db(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE company_sites SET version=1,updated_at=clock_timestamp()+interval '5 minutes',"
                    + "updated_by='PreviousActor' WHERE id='" + site + "'");
        }
        var detail = path(company) + "/" + site;
        var original = json.readTree(given().headers(headers(tenant, null)).get(detail)
                .then().statusCode(200).extract().asString()).path("data");
        var acceptedHeaders = headers(tenant, "clock-skew");
        acceptedHeaders.put("If-Match", "1");
        var replayHeaders = headers(tenant, "clock-skew");
        replayHeaders.put("If-Match", "1");
        replayHeaders.put("User-Id", "ReplayActor");
        for (var attempt : List.of(acceptedHeaders, replayHeaders)) {
            var response = given().headers(attempt).contentType("application/json").body("{}").patch(detail)
                    .then().statusCode(200).header("Process-Id", attempt.get("Process-Id")).extract().asString();
            assertEquals(original, json.readTree(response).path("data"));
        }
        try (var connection = db(); var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT created_at FROM company_idempotency_records WHERE tenant_id='" + tenant
                        + "' AND operation='company.site.update.v1'")) {
            assertTrue(rows.next());
            assertTrue(rows.getTimestamp(1).toInstant().isBefore(java.time.Instant.parse(original.path("updatedAt").asText())));
            assertFalse(rows.next());
        }
        try (var connection = db(); var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT count(*) FROM company_outbox_events WHERE aggregate_id='" + site
                        + "' AND event_type<>'company.site.created.v1'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    private JsonNode assertOriginalSite(UUID tenant, UUID company, String body) throws Exception {
        var accepted = given().headers(headers(tenant, "original")).contentType("application/json").body(body).post(path(company))
                .then().statusCode(201).extract().asString();
        var original = json.readTree(accepted).path("data");
        assertEquals(12, original.size());
        assertFalse(original.path("effectivelyOperational").asBoolean());
        assertEquals(0, original.path("version").asLong());
        assertEvent(tenant, original);
        return original;
    }
    private void assertModeLifecycle(Path work, String mode, MutationFixture mutationFixture, UUID tenant, UUID company, String site, String body, JsonNode original) throws Exception {
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        var directory=Path.of("build").toAbsolutePath();var command=new ArrayList<String>();
        if(Boolean.getBoolean("native.artifact"))command.add(directory.resolve("company-registry-service-1.0.0-SNAPSHOT-runner").toString());
        else command.add(Path.of(System.getProperty("java.home"),"bin/java").toString());
        ProcessEnvironmentResource.settings.forEach((key,value)->command.add("-D"+key+"="+value));
        command.addAll(List.of("-Dquarkus.profile=test","-Dquarkus.http.port="+port,"-Dquarkus.http.host=127.0.0.1",
                "-Dcompany-registry.idempotency.active-key-id=process-k2","-Dcompany-registry.outbox.mode="+mode,
                "-Dquarkus.rest-client.party-registry.url=http://127.0.0.1:9","-Dquarkus.rest-client.geographic-reference.url=http://127.0.0.1:9"));
        if(!Boolean.getBoolean("native.artifact"))command.addAll(List.of("-jar",directory.resolve("quarkus-app/quarkus-run.jar").toString()));
        var log=work.resolve("hu04-"+mode+".log");var process=new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var base="http://127.0.0.1:"+port;
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).until(()->{
                if(!process.isAlive())throw new IllegalStateException("Site restart failed: "+log);
                try{return request(client,base,"/q/health/ready","GET",null,Map.of()).statusCode()==200;}catch(java.io.IOException _){return false;}
            });
            replayMutations(client, base, mutationFixture);
            mutationFixture(client, base, mode);
            assertReplayAndHistory(client, base, company, site, tenant, body, original);
            assertTenantSitesAndValidation(client, base, body, mode);
            assertSystemEndpoints(client, base, tenant);
        }finally{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }
    private void assertReplayAndHistory(HttpClient client, String base, UUID company, String site, UUID tenant, String body, JsonNode original) throws Exception {
        var h=headers(tenant,"original");h.put("User-Id","Replay-Actor");
        var replay=request(client,base,path(company),"POST",body,h);assertEquals(201,replay.statusCode(),replay.body());assertEquals(original,json.readTree(replay.body()).path("data"));
        var history=request(client,base,path(company)+"/"+site,"GET",null,headers(tenant,null));
        assertEquals(200,history.statusCode());assertEquals("CLOSED",json.readTree(history.body()).path("data").path("status").asText());
    }
    private void assertTenantSitesAndValidation(HttpClient client, String base, String body, String mode) throws Exception {
        var ownTenant=UUID.randomUUID();var own=seed(ownTenant);
        var created=request(client,base,path(own),"POST",body,headers(ownTenant,"one"));assertEquals(201,created.statusCode(),created.body());
        var second=request(client,base,path(own),"POST","{\"code\":\"TWO\",\"name\":\"Two\"}",headers(ownTenant,"two"));assertEquals(201,second.statusCode(),second.body());
        var page=request(client,base,path(own)+"?limit=1","GET",null,headers(ownTenant,null));assertEquals(200,page.statusCode());
        var cursor=json.readTree(page.body()).path("nextCursor").asText();assertFalse(cursor.isBlank());
        var next=request(client,base,path(own)+"?limit=1&cursor="+cursor,"GET",null,headers(ownTenant,null));assertEquals(200,next.statusCode());
        assertNotEquals(json.readTree(page.body()).path("data").get(0).path("siteId"),json.readTree(next.body()).path("data").get(0).path("siteId"));
        var bad=request(client,base,path(own),"POST","{\"code\":\"x\",\"name\":\"y\",\"siteType\":null}",headers(ownTenant,"bad"));assertEquals(400,bad.statusCode());assertEquals("site-type-null-not-allowed",json.readTree(bad.body()).path("code").asText());
        try(var c=db();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM company_outbox_events WHERE tenant_id='"+ownTenant+"'")){r.next();assertEquals(mode.equals("disabled")?0:2,r.getInt(1));}
        if(!mode.equals("disabled"))assertEvent(ownTenant,json.readTree(created.body()).path("data"));
    }
    private void assertSystemEndpoints(HttpClient client, String base, UUID tenant) throws Exception {
        assertEquals(404,request(client,base,"/v1/_verification/success","GET",null,headers(tenant,null)).statusCode());
        assertTrue(request(client,base,"/q/openapi","GET",null,Map.of("Accept","application/json")).body().contains("registerCompanySite"));
    }
    private void assertEvent(UUID tenant,JsonNode accepted) throws Exception {
        var schema=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("docs/contracts/events/company.site.created.v1.schema.json")),com.networknt.schema.InputFormat.JSON);
        try(var c=db();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM company_outbox_events WHERE tenant_id='"+tenant+"' AND aggregate_id='"+accepted.path("siteId").asText()+"'")) {
            assertTrue(r.next());var e=json.createObjectNode();
            e.put("eventId",r.getString("id"));e.put("tenantId",r.getString("tenant_id"));e.put("aggregateType",r.getString("aggregate_type"));e.put("aggregateId",r.getString("aggregate_id"));
            e.put("aggregateVersion",r.getLong("aggregate_version"));e.put("eventType",r.getString("event_type"));e.put("eventSchemaVersion",r.getInt("event_schema_version"));
            e.put("occurredAt",r.getTimestamp("occurred_at").toInstant().toString());e.put("correlationId",r.getString("correlation_id"));e.putNull("causationId");e.set("payload",json.readTree(r.getString("payload")));
            assertTrue(schema.validate(e).isEmpty(),()->schema.validate(e).toString());
            assertEquals(accepted.path("siteId"),e.path("aggregateId"));assertEquals(accepted.path("createdAt"),e.path("occurredAt"));assertEquals(accepted.path("createdBy"),e.path("payload").path("createdBy"));
            assertEquals("PENDING",r.getString("status"));assertEquals(0,r.getInt("publish_attempts"));assertEquals(0,r.getLong("version"));assertFalse(r.next());
        }
    }
    private record AcceptedMutation(String action, String body, long expected, String key, JsonNode data) {}
    private record MutationFixture(UUID tenant, UUID company, String site, List<AcceptedMutation> attempts) {}

    private MutationFixture mutationFixture(HttpClient client, String base, String mode) throws Exception {
        var tenant = UUID.randomUUID(); var company = seed(tenant);
        var created = request(client, base, path(company), "POST", "{\"code\":\"SITE\",\"name\":\"NAME\",\"siteType\":\"HEADQUARTERS\"}", headers(tenant,"create"));
        assertEquals(201, created.statusCode(), created.body());
        var site = json.readTree(created.body()).path("data").path("siteId").asText();
        var attempts = new ArrayList<AcceptedMutation>();
        var actions = List.of("update", "update", "activate", "update", "deactivate", "close");
        long[] versions = {0,1,1,2,2,3};
        for (int i=0; i<actions.size(); i++) {
            var action = actions.get(i);
            if (i==3 || i==4) fixtureCompanyState(company,site,i==3 ? "ACTIVE" : "INACTIVE");
            String body = i==0 ? "{\"name\":\" compañía  ágil \"}" : action.equals("activate") ? null : "{}";
            var h = headers(tenant,"hu05-"+i); h.put("If-Match", Long.toString(versions[i]));
            var result = request(client, base, path(company)+"/"+site+(action.equals("update") ? "" : "/"+action),
                    action.equals("update") ? "PATCH" : "POST", body, h);
            assertEquals(200,result.statusCode(),result.body());
            assertEquals(h.get("Process-Id"), result.headers().firstValue("Process-Id").orElseThrow());
            var data = json.readTree(result.body()).path("data"); assertEquals(12,data.size());
            assertEquals(i==1 || i==3 ? versions[i] : versions[i]+1,data.path("version").asLong());
            assertEquals(i==3,data.path("effectivelyOperational").asBoolean());
            attempts.add(new AcceptedMutation(action,body,versions[i],"hu05-"+i,data));
        }
        try(var c=db();var statement=c.createStatement()) {
            statement.executeUpdate("UPDATE companies SET status='ARCHIVED',version=1 WHERE id='"+company+"'");
        }
        var fixture = new MutationFixture(tenant,company,site,attempts);
        replayMutations(client,base,fixture);
        assertMutationEvents(fixture,mode);
        return fixture;
    }

    private void fixtureCompanyState(UUID company, String site, String status) throws Exception {
        try(var connection=db();var statement=connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT id FROM companies WHERE id='"+company+"' FOR UPDATE");
            try(var row=statement.executeQuery("SELECT status,site_type FROM company_sites WHERE id='"+site+"' AND company_id='"+company+"' FOR UPDATE")) {
                assertTrue(row.next());
                if(status.equals("ACTIVE")) {
                    assertEquals("ACTIVE",row.getString(1)); assertEquals("HEADQUARTERS",row.getString(2));
                }
            }
            statement.executeUpdate("UPDATE companies SET status='"+status+"' WHERE id='"+company+"'");
            connection.commit();
        }
    }

    private void replayMutations(HttpClient client, String base, MutationFixture fixture) throws Exception {
        for (var attempt : fixture.attempts()) {
            var h = headers(fixture.tenant(),attempt.key()); h.put("If-Match", Long.toString(attempt.expected()));
            h.put("User-Id","Replay-Actor");
            var result = request(client,base,path(fixture.company())+"/"+fixture.site()+(attempt.action().equals("update") ? "" : "/"+attempt.action()),
                    attempt.action().equals("update") ? "PATCH" : "POST",attempt.body(),h);
            assertEquals(200,result.statusCode(),result.body());
            assertEquals(attempt.data(),json.readTree(result.body()).path("data"));
            assertEquals(h.get("Process-Id"),result.headers().firstValue("Process-Id").orElseThrow());
        }
    }

    private void assertMutationEvents(MutationFixture fixture, String mode) throws Exception {
        try(var c=db();var statement=c.createStatement();var rows=statement.executeQuery("SELECT * FROM company_outbox_events WHERE aggregate_id='"
                +fixture.site()+"' AND event_type<>'company.site.created.v1' ORDER BY aggregate_version")) {
            int count=0;
            while(rows.next()) {
                count++;
                var event=json.createObjectNode();
                event.put("eventId",rows.getString("id"));event.put("tenantId",rows.getString("tenant_id"));
                event.put("aggregateType",rows.getString("aggregate_type"));event.put("aggregateId",rows.getString("aggregate_id"));
                event.put("aggregateVersion",rows.getLong("aggregate_version"));event.put("eventType",rows.getString("event_type"));
                event.put("eventSchemaVersion",rows.getInt("event_schema_version"));event.put("occurredAt",rows.getTimestamp("occurred_at").toInstant().toString());
                event.put("correlationId",rows.getString("correlation_id"));event.putNull("causationId");event.set("payload",json.readTree(rows.getString("payload")));
                var type=rows.getString("event_type");
                var schema=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                        .getSchema(Files.readString(Path.of("docs/contracts/events/"+type+".schema.json")),com.networknt.schema.InputFormat.JSON);
                assertTrue(schema.validate(event).isEmpty(),()->schema.validate(event).toString());
                assertEquals(count,rows.getLong("aggregate_version"));
                assertEquals(count==4 ? "company.site.closed.v1" : "company.site.updated.v1",type);
                assertEquals(event.path("occurredAt"),event.path("payload").path("updatedAt"));
                assertEquals("Actor-aB",event.path("payload").path("updatedBy").asText());
            }
            assertEquals(mode.equals("disabled") ? 0 : 4,count);
        }
    }
}
