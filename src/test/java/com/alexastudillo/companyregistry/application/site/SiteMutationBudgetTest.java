package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.persistence.AtomicOperation;
import com.alexastudillo.companyregistry.application.persistence.CompanyRepository;
import com.alexastudillo.companyregistry.application.persistence.CompletionRepository;
import com.alexastudillo.companyregistry.application.persistence.PersistenceFailure;
import com.alexastudillo.companyregistry.application.persistence.SiteRepository;
import com.alexastudillo.companyregistry.domain.model.SiteInformationPatch;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SiteMutationBudgetTest {
    private static final Duration OBSERVER_LIMIT = Duration.ofSeconds(2);

    private final AtomicOperation atomic = mock(AtomicOperation.class);
    private final CompletionRepository completions = mock(CompletionRepository.class);
    private final MutateSite.Storage storage = new MutateSite.Storage(atomic, mock(CompanyRepository.class),
            mock(SiteRepository.class), mock(SiteMutationEvents.class));
    private final MutateSite.Idempotency idempotency = new MutateSite.Idempotency(completions,
            mock(SiteMutationFingerprints.class), mock(SiteMutationResults.class));

    private MutateSite service(Duration budget) {
        return new MutateSite(storage, idempotency, UUID::randomUUID, Clock.systemUTC(), budget);
    }

    private SiteMutation.Command command() {
        return new SiteMutation.Command(new OperationContext(UUID.randomUUID(), "Actor", UUID.randomUUID().toString()),
                UUID.randomUUID(), UUID.randomUUID(), 0, "key", SiteMutation.Operation.UPDATE, SiteInformationPatch.empty());
    }

    @Test void rejectsZeroNegativeNullAndOverflowingBudgets() {
        for (var budget : new Duration[] {null, Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> service(budget));
            assertEquals("site-mutation-budget-invalid", failure.getMessage());
        }
    }

    @Test void subscriptionDeadlineBoundsInitialAcquisitionWithoutStartingAWrite() {
        when(atomic.read(any(Duration.class), any())).thenReturn(Uni.createFrom().nothing());
        var service = service(Duration.ofMillis(20));
        var command = command();
        var result = service.execute(command);
        var awaiting = result.await();
        var failure = assertThrows(SiteMutationFailure.class, () -> awaiting.atMost(OBSERVER_LIMIT));
        assertEquals(SiteMutationFailure.Kind.UNAVAILABLE, failure.kind());
        verify(atomic, times(1)).read(any(Duration.class), any());
        verify(atomic, never()).execute(any(Duration.class), any());
    }

    @Test void uncertainFinalizationDoesNotOpenRecoveryOrReexecute() {
        when(atomic.checkpoint(any())).thenReturn(Uni.createFrom().voidItem());
        when(completions.find(any())).thenReturn(Uni.createFrom().nullItem());
        when(atomic.read(any(Duration.class), any())).thenAnswer(call -> {
            java.util.function.Supplier<Uni<Object>> work = call.getArgument(1);
            return work.get();
        });
        when(atomic.execute(any(Duration.class), any())).thenReturn(Uni.createFrom().failure(PersistenceFailure.finalizationUncertain()));
        var service = service(Duration.ofSeconds(1));
        var command = command();
        var result = service.execute(command);
        var awaiting = result.await();
        var failure = assertThrows(SiteMutationFailure.class, () -> awaiting.atMost(OBSERVER_LIMIT));
        assertEquals(SiteMutationFailure.Kind.UNAVAILABLE, failure.kind());
        verify(atomic, times(1)).read(any(Duration.class), any());
        verify(atomic, times(1)).execute(any(Duration.class), any());
        verify(completions, times(1)).find(any());
    }
}
