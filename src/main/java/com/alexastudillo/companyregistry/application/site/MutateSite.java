package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.company.DatabaseBudget;
import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.util.function.Supplier;

/**
 * Sequential local transaction; loser recovery starts only after rollback and
 * session close.
 */
public final class MutateSite {
    private static final Duration TIMER_GRANULARITY = Duration.ofMillis(1);

    public record Storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteMutationEvents events) {
    }

    public record Idempotency(CompletionRepository completions, SiteMutationFingerprints fingerprints,
            SiteMutationResults results) {
    }

    private final Storage storage;
    private final Idempotency idempotency;
    private final Identifiers ids;
    private final Clock clock;
    private final Duration waitBudget;

    public MutateSite(Storage storage, Idempotency idempotency, Identifiers ids, Clock clock, Duration waitBudget) {
        this.storage = storage;
        this.idempotency = idempotency;
        this.ids = ids;
        this.clock = clock;
        if (waitBudget == null) {
            throw new IllegalArgumentException("site-mutation-budget-invalid");
        }
        try {
            if (waitBudget.toNanos() <= 0) {
                throw new IllegalArgumentException("site-mutation-budget-invalid");
            }
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException("site-mutation-budget-invalid");
        }
        this.waitBudget = waitBudget;
    }

    public Uni<SiteMutation.Result> execute(SiteMutation.Command command) {
        return Uni.createFrom().deferred(() -> {
            long started = System.nanoTime();
            var budget = new DatabaseBudget(waitBudget);
            var key = command.idempotencyKey() == null ? null
                    : new CompletionKey(command.context().tenantId(), command.operation().namespace(),
                            command.idempotencyKey());
            return database(false, budget, () -> lookup(key, budget))
                    .chain(stored -> stored != null ? Uni.createFrom().item(replay(command, stored))
                            : persist(command, key, budget))
                    .invoke(_ -> {
                        if (System.nanoTime() - started >= waitBudget.toNanos()) {
                            throw new SiteMutationFailure(SiteMutationFailure.Kind.UNAVAILABLE);
                        }
                    });
        }).onFailure(SiteMutationRejected.class).transform(failure -> new SiteMutationFailure(
                SiteMutationFailure.Kind.valueOf((failure).reason().name())))
                .ifNoItem().after(waitBudget.compareTo(TIMER_GRANULARITY) < 0 ? TIMER_GRANULARITY : waitBudget)
                .failWith(() -> new SiteMutationFailure(SiteMutationFailure.Kind.UNAVAILABLE))
                .onFailure(failure -> failure instanceof PersistenceFailure persistence
                        && !persistence.transactionFinalized())
                .transform(_ -> new SiteMutationFailure(SiteMutationFailure.Kind.UNAVAILABLE))
                .onFailure(
                        failure -> failure instanceof com.alexastudillo.companyregistry.application.company.CompanyFailure company
                                && company
                                        .kind() == com.alexastudillo.companyregistry.application.company.CompanyFailure.Kind.UNAVAILABLE)
                .transform(_ -> new SiteMutationFailure(SiteMutationFailure.Kind.UNAVAILABLE));
    }

    private Uni<SiteMutation.Result> persist(SiteMutation.Command command, CompletionKey key,
            DatabaseBudget budget) {
        var input = SiteInformationPolicy.canonical(command.patch());
        return database(true, budget,
                () -> query(budget,
                        () -> storage.companies().findForUpdate(command.context().tenantId(), command.companyId()))
                        .chain(parent -> lookup(key, budget).chain(stored -> {
                            if (stored != null) {
                                return Uni.createFrom().item(replay(command, stored));
                            }
                            if (parent == null)
                                throw new SiteMutationFailure(SiteMutationFailure.Kind.COMPANY_NOT_FOUND);
                            return query(budget,
                                    () -> storage.sites().findForUpdate(command.context().tenantId(),
                                            command.companyId(), command.siteId()))
                                    .chain(site -> accept(command, key, parent, site, input, budget));
                        })))
                .onFailure(this::invalidWrite)
                .transform(_ -> new IllegalStateException("site-mutation-write-failed"))
                .onFailure(this::recoverable)
                .recoverWithUni(failure -> recover(command, key, budget, (PersistenceFailure) failure));
    }

    private Uni<CompletionRecord> lookup(CompletionKey key, DatabaseBudget budget) {
        return key == null ? Uni.createFrom().nullItem() : query(budget, () -> idempotency.completions().find(key));
    }

    private Uni<SiteMutation.Result> accept(SiteMutation.Command command, CompletionKey key,
            CompanyRecord parent, SiteRecord previous, SiteInformationPatch input, DatabaseBudget budget) {
        if (previous == null)
            throw new SiteMutationFailure(SiteMutationFailure.Kind.SITE_NOT_FOUND);
        if (previous.site().version().value() != command.expectedVersion())
            throw new SiteMutationFailure(SiteMutationFailure.Kind.STALE_VERSION);
        SiteLifecyclePolicy.requireEditable(parent.company().status(), previous.site().status());
        var information = new SiteInformation(previous.code(), previous.name(), previous.site().type());
        var decision = SiteInformationPolicy.decide(information, input);
        var target = command.operation() == SiteMutation.Operation.UPDATE ? previous.site().status()
                : SiteLifecyclePolicy.transition(parent.company().status(), previous.site().type(),
                        previous.site().status(), command.operation().target());
        SiteLifecyclePolicy.requireHeadquarters(parent.company().status(), previous.site().type(),
                previous.site().status(),
                decision.information().siteType(), target);
        boolean changed = decision.changed() || target != previous.site().status();
        var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var site = previous.site();
        var row = changed
                ? new SiteRecord(
                        new Site(site.id(), site.tenantId(), site.companyId(), decision.information().siteType(),
                                target, site.version().next()),
                        decision.information().code(), decision.information().name(),
                        new Audit(previous.audit().createdAt(), previous.audit().createdBy(), now,
                                command.context().actor()))
                : previous;
        var result = SiteMutation.Result.under(row, parent.company());
        return ensureAvailable(row, budget)
                .chain(() -> changed ? query(budget, () -> storage.sites().update(row, command.expectedVersion()))
                        : Uni.createFrom().item(row))
                .chain(() -> complete(command, key, result, now, budget))
                .chain(() -> changed
                        ? query(budget,
                                () -> storage.events().store(SiteMutated.candidate(ids.next(), row, command.context())))
                        : Uni.createFrom().nullItem())
                .replaceWith(result);
    }

    private Uni<Void> complete(SiteMutation.Command command, CompletionKey key, SiteMutation.Result result,
            Instant now, DatabaseBudget budget) {
        if (key == null)
            return Uni.createFrom().voidItem();
        var signature = idempotency.fingerprints().sign(command);
        var completion = new CompletionRecord(key, signature.hash(), command.companyId(), command.siteId(),
                command.expectedVersion(),
                (short) 200, idempotency.results().currentVersion(), idempotency.results().encode(result), now,
                command.context().actor(),
                signature.version(), signature.keyId());
        return query(budget, () -> idempotency.completions().insert(completion)).replaceWithVoid();
    }

    private Uni<Void> ensureAvailable(SiteRecord row, DatabaseBudget budget) {
        var site = row.site();
        return query(budget,
                () -> storage.sites().codeOccupiedByOther(site.tenantId(), site.companyId(), site.id(), row.code()))
                .chain(occupied -> {
                    if (Boolean.TRUE.equals(occupied))
                        throw new SiteMutationFailure(SiteMutationFailure.Kind.CODE_OCCUPIED);
                    return site.type() == SiteType.HEADQUARTERS && site.status() != SiteStatus.CLOSED
                            ? query(budget,
                                    () -> storage.sites().headquartersOccupiedByOther(site.tenantId(), site.companyId(),
                                            site.id()))
                            : Uni.createFrom().item(false);
                }).invoke(occupied -> {
                    if (Boolean.TRUE.equals(occupied))
                        throw new SiteMutationFailure(SiteMutationFailure.Kind.HEADQUARTERS_OCCUPIED);
                }).replaceWithVoid();
    }

    private Uni<SiteMutation.Result> recover(SiteMutation.Command command, CompletionKey key,
            DatabaseBudget budget, PersistenceFailure failure) {
        return database(false, budget, () -> lookup(key, budget).chain(stored -> {
            if (stored != null)
                return Uni.createFrom().item(replay(command, stored));
            if (failure.kind() == PersistenceFailure.Kind.STALE_VERSION)
                return Uni.createFrom().failure(new SiteMutationFailure(SiteMutationFailure.Kind.STALE_VERSION));
            var kind = switch (failure.uniqueness()) {
                case SITE_CODE -> SiteMutationFailure.Kind.CODE_OCCUPIED;
                case SITE_HEADQUARTERS -> SiteMutationFailure.Kind.HEADQUARTERS_OCCUPIED;
                default -> SiteMutationFailure.Kind.UNAVAILABLE;
            };
            return Uni.createFrom().failure(new SiteMutationFailure(kind));
        }));
    }

    private SiteMutation.Result replay(SiteMutation.Command command, CompletionRecord stored) {
        if (stored.fingerprintVersion() == null || stored.fingerprintKeyId() == null || stored.resultStatus() != 200)
            throw new IllegalStateException("historical-site-completion-invalid");
        if (!idempotency.fingerprints().matches(command, stored.fingerprintVersion(), stored.fingerprintKeyId(),
                stored.requestHash()))
            throw new SiteMutationFailure(SiteMutationFailure.Kind.IDEMPOTENCY_MISMATCH);
        if (!command.companyId().equals(stored.companyId())
                || !command.context().tenantId().equals(stored.key().tenantId())
                || !command.siteId().equals(stored.siteId())
                || !command.operation().namespace().equals(stored.key().operation())
                || !command.idempotencyKey().equals(stored.key().key()))
            throw new IllegalStateException("historical-site-completion-invalid");
        return idempotency.results().decode(stored);
    }

    private boolean invalidWrite(Throwable failure) {
        return failure instanceof PersistenceFailure p && (p.kind() == PersistenceFailure.Kind.INVALID_DATA
                || p.kind() == PersistenceFailure.Kind.INVALID_REFERENCE);
    }

    private boolean recoverable(Throwable failure) {
        return failure instanceof PersistenceFailure p && p.transactionFinalized()
                && (p.kind() == PersistenceFailure.Kind.DUPLICATE
                        || p.kind() == PersistenceFailure.Kind.UNAVAILABLE
                        || p.kind() == PersistenceFailure.Kind.STALE_VERSION);
    }

    private <T> Uni<T> query(DatabaseBudget budget, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> storage.atomic().checkpoint(budget.remaining()).chain(work::get));
    }

    private <T> Uni<T> database(boolean write, DatabaseBudget budget, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> {
            budget.start();
            var remaining = budget.remaining();
            return (write ? storage.atomic().execute(remaining, work) : storage.atomic().read(remaining, work))
                    .eventually(budget::pause);
        });
    }
}
