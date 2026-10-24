package com.alexastudillo.companyregistry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs unchanged against the productive JVM artifact and native executable. */
@QuarkusIntegrationTest @QuarkusTestResource(value = ProcessEnvironmentResource.class, restrictToAnnotatedClass = true)
class PackagedUpdateIT {
    private final ObjectMapper json=new ObjectMapper();
    Connection database() throws Exception {return DriverManager.getConnection(ProcessEnvironmentResource.settings.get("quarkus.datasource.jdbc.url"),"company_registry_migration","migration_test");}
    UUID seed(UUID tenant,String code) throws Exception {
        try(var c=database();var s=c.createStatement();var r=s.executeQuery("INSERT INTO companies(tenant_id,party_id,code,operating_name,description,logo_url,created_by,updated_by) VALUES('"+tenant+"',uuidv7(),'"+code+"','COMPANY','DESCRIPTION','https://host/LogoAb.png','Creator','Creator') RETURNING id")){r.next();return UUID.fromString(r.getString(1));}
    }
    Map<String,String> headers(UUID tenant,long version,String key) {
        var h=new HashMap<String,String>();h.put("Tenant-Id",tenant.toString());h.put("Process-Id",UUID.randomUUID().toString());h.put("User-Id","Actor-aB");h.put("If-Match",Long.toString(version));if(key!=null)h.put("Idempotency-Key",key);return h;
    }
    @Test void localPatchNoopAndReplaySurviveRestartRotationAndAllModes(@TempDir Path work) throws Exception {
        ProcessEnvironmentResource.stub.reset();var tenant=UUID.randomUUID();var id=seed(tenant,"PATCH");
        var h=headers(tenant,0,"change");var body="{\"operatingName\":\" compañía ágil \",\"description\":null,\"logoUrl\":null}";
        var accepted=given().headers(h).contentType("application/json").body(body).patch("/v1/companies/"+id).then().statusCode(200).extract().asString();
        var original=json.readTree(accepted).path("data");assertFalse(original.has("party"));assertFalse(original.has("description"));assertFalse(original.has("logoUrl"));assertEquals(1,original.path("version").asInt());
        var noop=given().headers(headers(tenant,1,"noop")).contentType("application/json").body("{}").patch("/v1/companies/"+id).then().statusCode(200).extract().asString();
        assertEquals(original,json.readTree(noop).path("data"));
        try(var c=database();var s=c.createStatement()){s.execute("UPDATE companies SET operating_name='LATER',status='ARCHIVED',version=version+1,updated_by='Later' WHERE id='"+id+"'");}
        assertTrue(ProcessEnvironmentResource.stub.requests().isEmpty());
        assertUpdateEvent(tenant, original);
        for(var mode:List.of("disabled","stored-only","published")) {
            int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
            var directory=Path.of(Boolean.getBoolean("verification.artifact")?"build/verification":"build").toAbsolutePath();var command=new ArrayList<String>();
            if(Boolean.getBoolean("native.artifact"))command.add(directory.resolve("company-registry-service-1.0.0-SNAPSHOT-runner").toString());
            else command.add(Path.of(System.getProperty("java.home"),"bin/java").toString());
            ProcessEnvironmentResource.settings.forEach((key,value)->command.add("-D"+key+"="+value));
            command.addAll(List.of("-Dquarkus.profile=test","-Dquarkus.http.port="+port,"-Dquarkus.http.host=127.0.0.1",
                    "-Dcompany-registry.idempotency.active-key-id=process-k2","-Dcompany-registry.outbox.mode="+mode,
                    "-Dquarkus.rest-client.party-registry.url=http://127.0.0.1:9","-Dquarkus.rest-client.geographic-reference.url=http://127.0.0.1:9"));
            if(!Boolean.getBoolean("native.artifact"))command.addAll(List.of("-jar",directory.resolve("quarkus-app/quarkus-run.jar").toString()));
            var log=work.resolve("hu02-"+mode+".log");var process=new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                var base="http://127.0.0.1:"+port;
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).until(()->{
                    if(!process.isAlive())throw new IllegalStateException("HU02 restart failed: "+log);
                    try{return client.send(HttpRequest.newBuilder(URI.create(base+"/q/health/ready")).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(java.io.IOException _){return false;}
                });
                var replay=patch(client,base,id,headers(tenant,0,"change"),body);assertEquals(200,replay.statusCode(),replay.body());assertEquals(original,json.readTree(replay.body()).path("data"));
                assertEquals(original,json.readTree(patch(client,base,id,headers(tenant,1,"noop"),"{}").body()).path("data"));
                var ownTenant=UUID.randomUUID();var own=seed(ownTenant,"MODE");var changed=patch(client,base,own,headers(ownTenant,0,null),"{\"operatingName\":\"new\"}");assertEquals(200,changed.statusCode(),changed.body());
                assertEquals(1,json.readTree(changed.body()).path("data").path("version").asInt());
                try(var c=database();var s=c.createStatement();var r=s.executeQuery("SELECT (SELECT count(*) FROM company_outbox_events WHERE tenant_id='"+ownTenant+"'),(SELECT count(*) FROM company_idempotency_records WHERE tenant_id='"+ownTenant+"')")){r.next();assertEquals(mode.equals("disabled")?0:1,r.getInt(1));assertEquals(0,r.getInt(2));}
                var contract=client.send(HttpRequest.newBuilder(URI.create(base+"/q/openapi")).header("Accept","application/json").build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,contract.statusCode());assertTrue(contract.body().contains("updateCompanyInformation"));
                var fixtureRequest=HttpRequest.newBuilder(URI.create(base+"/v1/_verification/success"));headers(tenant,0,null).forEach(fixtureRequest::header);
                var fixture=client.send(fixtureRequest.build(),HttpResponse.BodyHandlers.discarding());assertEquals(404,fixture.statusCode());
            } finally {process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
        }
        try(var c=database();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM company_outbox_events WHERE tenant_id='"+tenant+"'")){r.next();assertEquals(1,r.getInt(1));}
        assertTrue(ProcessEnvironmentResource.stub.requests().isEmpty());
    }
    HttpResponse<String> patch(HttpClient client,String base,UUID id,Map<String,String> headers,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/v1/companies/"+id)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");headers.forEach(request::header);
        return client.send(request.method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertUpdateEvent(UUID tenant, JsonNode accepted) throws Exception {
        var schema=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("docs/contracts/events/company.updated.v1.schema.json")),com.networknt.schema.InputFormat.JSON);
        try(var c=database();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM company_outbox_events WHERE tenant_id='"+tenant+"'")) {
            assertTrue(r.next());var event=json.createObjectNode();
            event.put("eventId",r.getString("id"));event.put("tenantId",r.getString("tenant_id"));
            event.put("aggregateType",r.getString("aggregate_type"));event.put("aggregateId",r.getString("aggregate_id"));
            event.put("aggregateVersion",r.getLong("aggregate_version"));event.put("eventType",r.getString("event_type"));
            event.put("eventSchemaVersion",r.getInt("event_schema_version"));event.put("occurredAt",r.getTimestamp("occurred_at").toInstant().toString());
            event.put("correlationId",r.getString("correlation_id"));event.putNull("causationId");event.set("payload",json.readTree(r.getString("payload")));
            assertTrue(schema.validate(event).isEmpty(),()->schema.validate(event).toString());
            assertEquals(accepted.path("companyId"),event.path("payload").path("companyId"));
            assertEquals(accepted.path("updatedAt"),event.path("occurredAt"));assertEquals(accepted.path("updatedBy"),event.path("payload").path("updatedBy"));
            assertEquals("PENDING",r.getString("status"));assertEquals(0,r.getInt("publish_attempts"));assertEquals(0,r.getLong("version"));assertFalse(r.next());
        }
    }

}
