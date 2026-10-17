package com.alexastudillo.companyregistry.infrastructure.reference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.alexastudillo.companyregistry.application.company.PartyView;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

class PartyEnrichmentLimitsTest {
    private final OperationContext context = new OperationContext(UUID.randomUUID(),"Actor-aB",UUID.randomUUID().toString());
    private PartyReadAdapter adapter(PartyClient client) {
        var adapter=new PartyReadAdapter(); adapter.client=client; adapter.concurrency=3; adapter.deadline=Duration.ofSeconds(5); return adapter;
    }
    private static PartyReadModels.Detail detail(UUID id) {
        var now=Instant.parse("2026-10-17T14:30:00Z");
        return new PartyReadModels.Detail(id,"LEGAL_ENTITY","NAME","ACTIVE",0L,now,now,"actor","actor",new PartyReadModels.Legal("NAME",null,null,"EC",null,null));
    }
    @Test void limitsConcurrentRequestsAndRestoresInputOrder() {
        var client=mock(PartyClient.class); var adapter=adapter(client);
        var pending=new ArrayList<Runnable>(); var active=new AtomicInteger(); var max=new AtomicInteger();
        when(client.detail(any(),anyString(),anyString(),anyString())).thenAnswer(call -> {
            UUID id=call.getArgument(0);
            return Uni.createFrom().emitter(emitter->{
                max.accumulateAndGet(active.incrementAndGet(),Math::max);
                pending.add(()->{active.decrementAndGet();emitter.complete(RestResponse.ok(new RemoteModels.Envelope<>(200,"successful",detail(id))));});
            });
        });
        when(client.identifiers(any(),anyString(),anyString(),anyString(),eq(200),isNull()))
                .thenAnswer(ignored -> Uni.createFrom().item(RestResponse.ok(new PartyReadModels.Page(200,"successful",List.of(),null,0))));
        var ids=java.util.stream.IntStream.range(0,12).mapToObj(i->UUID.randomUUID()).toList();
        var future=adapter.findAll(context,ids).subscribeAsCompletionStage();
        assertEquals(3,pending.size());
        // Complete newest requests first to exercise out-of-order arrival without timing assumptions.
        while(!pending.isEmpty()) pending.removeLast().run();
        assertEquals(ids,future.toCompletableFuture().join().stream().map(PartyView::partyId).toList());
        assertEquals(3,max.get()); assertEquals(0,active.get());
    }
    @Test void sharedDeadlineAndCallerCancellationCancelOutstandingHttpWork() {
        for(var byDeadline:List.of(true,false)) {
            var client=mock(PartyClient.class); var adapter=adapter(client); adapter.deadline=Duration.ofMillis(50);
            var cancelled=new AtomicBoolean();
            when(client.detail(any(),anyString(),anyString(),anyString())).thenReturn(Uni.createFrom().emitter(emitter->emitter.onTermination(()->cancelled.set(true))));
            if(byDeadline) {
                var awaitable = adapter.find(context, UUID.randomUUID()).await();
                var timeout = Duration.ofSeconds(2);
                var failure = assertThrows(ReferenceFailure.class, () -> awaitable.atMost(timeout));
                assertEquals(ReferenceFailure.Kind.TIMEOUT, failure.kind());
            } else {
                var subscription=adapter.find(context,UUID.randomUUID()).subscribe().with(ignored->fail(),ignored->fail()); subscription.cancel();
            }
            assertTrue(cancelled.get());
            verify(client,never()).identifiers(any(),anyString(),anyString(),anyString(),anyInt(),any());
        }
    }
}
