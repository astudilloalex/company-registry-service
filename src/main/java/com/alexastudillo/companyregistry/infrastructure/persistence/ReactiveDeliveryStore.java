package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.outbox.DeliveryFailure;
import com.alexastudillo.companyregistry.application.outbox.DeliveryStore;
import com.alexastudillo.companyregistry.application.outbox.StoredEvent;
import com.alexastudillo.companyregistry.domain.model.OutboxStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Each call owns a short transaction. No connection or row lock crosses a broker wait. */
@ApplicationScoped
public class ReactiveDeliveryStore implements DeliveryStore {
    private static final String ACTOR = "outbox-publisher";
    public static final String SELECTION = """
            select * from company_outbox_events
            where status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= :now)
            order by coalesce(next_attempt_at, created_at), created_at, id
            limit :limit for update skip locked
            """;

    @Override
    public Uni<Batch> reserve(int limit, int maximumAttempts, Duration lease) {
        return Panache.withTransaction(() -> Panache.getSession().chain(session ->
                session.createNativeQuery("select clock_timestamp()", OffsetDateTime.class).getSingleResult()
                        .chain(time -> session.createNativeQuery(SELECTION, OutboxEntity.class)
                                .setParameter("now", time.toInstant()).setParameter("limit", limit).getResultList()
                                .chain(rows -> {
                                    var claimed = new ArrayList<OutboxEntity>();
                                    int exhausted = reserveRows(rows, claimed, maximumAttempts, lease, time.toInstant());
                                    return session.flush().map(ignored -> new Batch(claimed.stream()
                                            .map(ReactiveDeliveryStore::claim).toList(), exhausted));
                                }))));
    }

    private static int reserveRows(List<OutboxEntity> rows, List<OutboxEntity> claimed,
            int maximumAttempts, Duration lease, Instant now) {
        int exhausted = 0;
        var claimedAt = now.truncatedTo(ChronoUnit.MICROS);
        for (var row : rows) {
            row.updatedAt = now.isBefore(row.updatedAt) ? row.updatedAt : now;
            row.updatedBy = ACTOR;
            if (row.attempts >= maximumAttempts) {
                row.status = OutboxStatus.FAILED;
                row.errorCode = row.errorCode == null ? DeliveryFailure.Code.DELIVERY_OUTCOME_UNKNOWN.diagnostic()
                        : safeCode(row.errorCode);
                row.errorDetail = row.errorCode;
                row.nextAttemptAt = null;
                exhausted++;
            } else {
                row.attempts++;
                row.lastAttemptAt = claimedAt;
                row.nextAttemptAt = claimedAt.plusNanos(micros(lease) * 1000);
                row.errorCode = null;
                row.errorDetail = null;
                claimed.add(row);
            }
        }
        return exhausted;
    }

    private static String safeCode(String code) {
        for (var allowed : DeliveryFailure.Code.values()) if (allowed.diagnostic().equals(code)) return code;
        return DeliveryFailure.Code.ATTEMPTS_EXHAUSTED.diagnostic();
    }

    private static Claim claim(OutboxEntity row) {
        return new Claim(StoredEvent.from(row.value()), new Reservation(row.id, row.tenantId, row.attempts,
                row.lastAttemptAt, row.nextAttemptAt, row.version));
    }

    @Override
    public Uni<Boolean> finish(Reservation reservation, Outcome outcome, Duration backoff, DeliveryFailure.Code error) {
        String changes = switch (outcome) {
            case PUBLISHED -> "status='PUBLISHED', published_at=clock_timestamp(), next_attempt_at=null, last_error_code=null, last_error_detail=null";
            case RETRY -> "next_attempt_at=clock_timestamp() + :delay * interval '1 microsecond', last_error_code=:error, last_error_detail=:error";
            case FAILED -> "status='FAILED', next_attempt_at=null, last_error_code=:error, last_error_detail=:error";
        };
        String sql = "update company_outbox_events set " + changes + """
                , updated_at=greatest(clock_timestamp(), updated_at), updated_by='outbox-publisher', version=version+1
                where id=:id and tenant_id=:tenant and status='PENDING' and publish_attempts=:attempt
                and version=:version and last_attempt_at=:claimed and next_attempt_at=:expires
                """;
        return Panache.withTransaction(() -> Panache.getSession().chain(session -> {
            var query = session.createNativeQuery(sql).setParameter("id", reservation.eventId())
                    .setParameter("tenant", reservation.tenantId()).setParameter("attempt", reservation.attempt())
                    .setParameter("version", reservation.technicalVersion()).setParameter("claimed", reservation.claimedAt())
                    .setParameter("expires", reservation.expiresAt());
            if (outcome != Outcome.PUBLISHED) query.setParameter("error", error.diagnostic());
            if (outcome == Outcome.RETRY) query.setParameter("delay", micros(backoff));
            return query.executeUpdate().map(count -> count == 1);
        }));
    }

    private static long micros(Duration value) { return Math.max(1, (value.toNanos() + 999) / 1000); }

    @Override
    public Uni<Backlog> backlog() {
        return Panache.withSession(() -> Panache.getSession().chain(session -> session.createNativeQuery("""
                select count(*), coalesce(greatest(0, extract(epoch from clock_timestamp()-min(occurred_at))),0)
                from company_outbox_events where status='PENDING'
                """, Object[].class).getSingleResult()
                .map(row -> new Backlog(((Number) row[0]).longValue(), ((Number) row[1]).doubleValue()))));
    }
}
