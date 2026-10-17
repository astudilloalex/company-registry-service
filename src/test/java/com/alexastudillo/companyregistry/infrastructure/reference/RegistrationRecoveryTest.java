package com.alexastudillo.companyregistry.infrastructure.reference;

import static org.junit.jupiter.api.Assertions.*;
import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.infrastructure.database.PostgresResource;
import com.alexastudillo.companyregistry.infrastructure.persistence.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest @QuarkusTestResource(PostgresResource.class) @QuarkusTestResource(ReferenceStubResource.class)
@TestProfile(CompanyRegistrationHttpTest.Profile.class)
class RegistrationRecoveryTest {
    @Inject AtomicOperation atomic; @Inject CompanyRepository companies; @Inject CompletionRepository completions;
    @Inject PartyReads parties; @Inject Fingerprints fingerprints; @Inject AcceptedResults results; @Inject CreationEvents events;
    @Inject Identifiers ids; @Inject Clock clock; @Inject Flyway flyway; @Inject io.vertx.core.Vertx vertx;
    private RegisterCompany service(AtomicOperation operation,Fingerprints signing) {
        return new RegisterCompany(new RegisterCompany.Storage(operation, companies, events),
                new RegisterCompany.Idempotency(completions, signing, results), parties, ids, clock);
    }
    private CompanyRegistration.Command command(UUID tenant,UUID party,String key) {return new CompanyRegistration.Command(new OperationContext(tenant,"Actor-aB",UUID.randomUUID().toString()),key,party,"acme","name",null,null);}
    private void provider(UUID party) {var stub=ReferenceStubResource.stub;stub.reply("/v1/parties/"+party,200,PartyReadAdapterTest.detail(party,"LEGAL_ENTITY","ACTIVE"));stub.reply("/v1/parties/"+party+"/identifiers",200,PartyReadAdapterTest.page("",0,null));}
    private <T> CompletableFuture<T> invoke(Supplier<Uni<T>> operation) {
        var future=new CompletableFuture<T>();
        var context=io.smallrye.common.vertx.VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle.setContextSafe(context,true);
        context.runOnContext(ignored->{try {operation.get().subscribe().with(future::complete,future::completeExceptionally);}catch(Throwable failure){future.completeExceptionally(failure);}});return future;
    }
    @Test void durableLookupRecoversAFailureReportedAfterTheActualCommit() throws Exception {
        var party=UUID.randomUUID();provider(party);var command=command(UUID.randomUUID(),party,"lost-response");
        var failing=new AtomicOperation() {
            @Override public <T> Uni<T> execute(Supplier<Uni<T>> operation) {return atomic.execute(operation);}
            @Override public <T> Uni<T> read(Supplier<Uni<T>> operation) {return atomic.read(operation);}
            @Override public <T> Uni<T> read(Duration timeout,Supplier<Uni<T>> operation) {return atomic.read(timeout,operation);}
            @Override public Uni<Void> checkpoint(Duration remaining) {return atomic.checkpoint(remaining);}
            @Override public <T> Uni<T> execute(Duration timeout,Supplier<Uni<T>> operation) {
                return atomic.execute(timeout,operation).chain(ignored->Uni.createFrom().failure(new PersistenceFailure(PersistenceFailure.Kind.UNAVAILABLE,null)));
            }
        };
        var recovered=invoke(()->service(failing,fingerprints).execute(command)).get(10,TimeUnit.SECONDS);
        ReferenceStubResource.stub.reset();var replay=invoke(()->service(atomic,fingerprints).execute(command)).get(10,TimeUnit.SECONDS);
        assertEquals(recovered,replay);assertTrue(ReferenceStubResource.stub.requests().isEmpty());
    }
    @Test void rotatingCompetitorsCompareUsingTheWinningCompletionsHistoricalKey() throws Exception {
        var party=UUID.randomUUID();provider(party);var tenant=UUID.randomUUID();var command=command(tenant,party,"rotation-race");
        var keys=Map.of("K1",Base64.getEncoder().encodeToString(new byte[32]),"K2",Base64.getEncoder().encodeToString("different-rotation-key-material02".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var first=service(atomic,new ConfiguredFingerprints(new HmacKeys("K1",keys)));var second=service(atomic,new ConfiguredFingerprints(new HmacKeys("K2",keys)));
        try(var holder=flyway.getConfiguration().getDataSource().getConnection();var statement=holder.createStatement()) {
            holder.setAutoCommit(false);statement.execute("LOCK TABLE companies IN SHARE MODE");
            var a=invoke(()->first.execute(command));var b=invoke(()->second.execute(command));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->blocked("relation='companies'::regclass AND mode='RowExclusiveLock'")>=2);
            holder.rollback();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var row=s.executeQuery("SELECT fingerprint_key_id FROM company_idempotency_records WHERE tenant_id='"+tenant+"'")) {assertTrue(row.next());assertTrue(keys.containsKey(row.getString(1)));assertFalse(row.next());}
    }
    @Test void rollbackOfTheTransactionHoldingUniquenessAllowsTheEligibleRequestToCommit() throws Exception {
        var party=UUID.randomUUID();provider(party);var tenant=UUID.randomUUID();var command=command(tenant,party,"holder-rollback");
        try(var holder=flyway.getConfiguration().getDataSource().getConnection();var statement=holder.createStatement()) {
            holder.setAutoCommit(false);statement.execute("INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('"+tenant+"','"+party+"','ACME','HOLDER','a','a')");
            var pending=invoke(()->service(atomic,fingerprints).execute(command));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->blocked("locktype='transactionid'")>=1);
            holder.rollback();assertEquals("NAME",pending.get(10,TimeUnit.SECONDS).company().operatingName());
        }
    }
    private long blocked(String predicate) throws Exception {
        try(var c=flyway.getConfiguration().getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted AND "+predicate)){r.next();return r.getLong(1);}
    }
}
