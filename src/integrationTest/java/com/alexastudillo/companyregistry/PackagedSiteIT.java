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
        var body="{\"code\":\" ß \",\"name\":\" compañía  ágil \"}";
        var accepted=given().headers(headers(tenant,"original")).contentType("application/json").body(body).post(path(company))
                .then().statusCode(201).extract().asString();
        var original=json.readTree(accepted).path("data");assertEquals(12,original.size());assertFalse(original.path("effectivelyOperational").asBoolean());assertEquals(0,original.path("version").asLong());
        var site=original.path("siteId").asText();
        assertEvent(tenant,original);
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
                var h=headers(tenant,"original");h.put("User-Id","Replay-Actor");
                var replay=request(client,base,path(company),"POST",body,h);assertEquals(201,replay.statusCode(),replay.body());assertEquals(original,json.readTree(replay.body()).path("data"));
                var history=request(client,base,path(company)+"/"+site,"GET",null,headers(tenant,null));
                assertEquals(200,history.statusCode());assertEquals("CLOSED",json.readTree(history.body()).path("data").path("status").asText());
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
                assertEquals(404,request(client,base,"/v1/_verification/success","GET",null,headers(tenant,null)).statusCode());
                assertTrue(request(client,base,"/q/openapi","GET",null,Map.of("Accept","application/json")).body().contains("registerCompanySite"));
            }finally{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
        }
        assertTrue(ProcessEnvironmentResource.stub.requests().isEmpty());assertEvent(tenant,original);
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
}
