package com.alexastudillo.companyregistry.application.outbox;

import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryStore {
    record Reservation(UUID eventId, UUID tenantId, int attempt, Instant claimedAt,
                       Instant expiresAt, long technicalVersion) {}
    record Claim(StoredEvent event, Reservation reservation) {}
    record Batch(List<Claim> claims, int exhausted) { public Batch { claims = List.copyOf(claims); } }
    enum Outcome { PUBLISHED, RETRY, FAILED }
    record Backlog(long pending, double oldestAgeSeconds) {}

    Uni<Batch> reserve(int limit, int maximumAttempts, Duration lease);
    /** Completes only after commit; false means a stale or already completed reservation. */
    Uni<Boolean> finish(Reservation reservation, Outcome outcome, Duration backoff, DeliveryFailure.Code error);
    Uni<Backlog> backlog();
}
