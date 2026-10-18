package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.reference.*;
import com.alexastudillo.companyregistry.application.reference.References.PartyReference;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

/** Protected local phases, with Party HTTP strictly between finalized transactions. */
public final class TransitionCompany {
    public record Storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites, LifecycleEvents events) {}
    public record Idempotency(CompletionRepository completions, LifecycleFingerprints fingerprints, LifecycleResults results) {}
    private record Preparation(CompanyRecord company, boolean replayed, CompanyStatus target) {}
    private record PartyObservation(PartyReference party, ReferenceFailure failure) {}

    private final Storage storage;
    private final Idempotency idempotency;
    private final PartyReferences parties;
    private final Identifiers ids;
    private final Clock clock;
    private final Duration waitBudget;

    public TransitionCompany(Storage storage, Idempotency idempotency, PartyReferences parties,
            Identifiers ids, Clock clock, Duration waitBudget) {
        this.storage = storage;
        this.idempotency = idempotency;
        this.parties = parties;
        this.ids = ids;
        this.clock = clock;
        this.waitBudget = Duration.ofNanos(LifecycleDeadline.validate(waitBudget));
    }

    public Uni<CompanyRecord> execute(CompanyLifecycle.Command command) {
        return Uni.createFrom().deferred(() -> {
            var deadline = new LifecycleDeadline(waitBudget);
            var key = command.idempotencyKey() == null ? null
                    : new CompletionKey(command.context().tenantId(), command.operation().namespace(), command.idempotencyKey());
            return database(false, deadline, () -> lookup(key, deadline))
                    .chain(stored -> stored != null ? Uni.createFrom().item(replay(command, stored))
                            : begin(command, key, deadline))
                    .invoke(_ -> deadline.check());
        }).onFailure(CompanyLifecycleRejected.class).transform(failure -> new CompanyLifecycleFailure(
                CompanyLifecycleFailure.Kind.valueOf(failure.reason().name())))
                .onFailure(failure -> failure instanceof PersistenceFailure p
                        && (!p.transactionFinalized() || p.kind() == PersistenceFailure.Kind.UNAVAILABLE))
                .transform(_ -> new CompanyLifecycleFailure(CompanyLifecycleFailure.Kind.UNAVAILABLE));
    }

    private Uni<CompanyRecord> begin(CompanyLifecycle.Command command, CompletionKey key, LifecycleDeadline deadline) {
        if (command.operation() != CompanyLifecycle.Operation.ACTIVATE) return persist(command, key, null, deadline);
        return database(true, deadline, () -> protectedCompany(command, key, deadline)
                .chain(prepared -> prepared.replayed() ? Uni.createFrom().item(prepared)
                        : headquarters(command, deadline).replaceWith(prepared)))
                .chain(prepared -> prepared.replayed() ? Uni.createFrom().item(prepared.company())
                        : observe(command, prepared.company().company().partyId(), deadline)
                                .chain(observation -> persist(command, key, observation, deadline)));
    }

    private Uni<PartyObservation> observe(CompanyLifecycle.Command command, UUID partyId, LifecycleDeadline deadline) {
        return Uni.createFrom().deferred(() -> parties.find(command.context(), partyId)
                .ifNoItem().after(deadline.remaining()).failWith(() -> new ReferenceFailure(ReferenceFailure.Kind.TIMEOUT)))
                .map(party -> {
                    if (party != null && !partyId.equals(party.id())) throw new ReferenceFailure(ReferenceFailure.Kind.INVALID_CONTRACT);
                    return new PartyObservation(party, null);
                }).onFailure(ReferenceFailure.class).recoverWithItem(failure -> new PartyObservation(null, failure));
    }

    private Uni<CompanyRecord> persist(CompanyLifecycle.Command command, CompletionKey key,
            PartyObservation observation, LifecycleDeadline deadline) {
        return database(true, deadline, () -> protectedCompany(command, key, deadline).chain(prepared -> {
            if (prepared.replayed()) return Uni.createFrom().item(prepared.company());
            var ready = command.operation() == CompanyLifecycle.Operation.ACTIVATE
                    ? headquarters(command, deadline).invoke(_ -> eligible(observation))
                    : Uni.createFrom().voidItem();
            return ready.chain(() -> accept(command, key, prepared, deadline));
        })).onFailure(this::invalidWrite).transform(_ -> new IllegalStateException("company-lifecycle-write-failed"))
                .onFailure(this::recoverable).recoverWithUni(failure -> recover(command, key, deadline, (PersistenceFailure) failure));
    }

    private Uni<Preparation> protectedCompany(CompanyLifecycle.Command command, CompletionKey key, LifecycleDeadline deadline) {
        return query(deadline, () -> storage.companies().findForUpdate(command.context().tenantId(), command.companyId()))
                .chain(company -> lookup(key, deadline).map(stored -> {
                    if (stored != null) {
                        var accepted = replay(command, stored);
                        return new Preparation(accepted, true, accepted.company().status());
                    }
                    if (company == null) throw new CompanyLifecycleFailure(CompanyLifecycleFailure.Kind.COMPANY_NOT_FOUND);
                    if (company.company().version().value() != command.expectedVersion()) {
                        throw new CompanyLifecycleFailure(CompanyLifecycleFailure.Kind.STALE_VERSION);
                    }
                    var target = CompanyLifecyclePolicy.transition(company.company().status(), command.operation().target());
                    return new Preparation(company, false, target);
                }));
    }

    private Uni<Void> headquarters(CompanyLifecycle.Command command, LifecycleDeadline deadline) {
        return query(deadline, () -> storage.sites().currentHeadquartersForUpdate(command.context().tenantId(), command.companyId()))
                .invoke(site -> CompanyActivationPolicy.requireHeadquarters(site == null ? null : site.site().status()))
                .replaceWithVoid();
    }

    private static void eligible(PartyObservation observation) {
        if (observation.failure() != null) throw observation.failure();
        var party = observation.party();
        CompanyActivationPolicy.requireParty(party == null ? null
                : new CompanyActivationPolicy.PartyFacts(party.legalEntity(), party.active()));
    }

    private Uni<CompanyRecord> accept(CompanyLifecycle.Command command, CompletionKey key, Preparation prepared,
            LifecycleDeadline deadline) {
        var previous = prepared.company();
        var company = previous.company();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var row = new CompanyRecord(new Company(company.id(), company.tenantId(), company.partyId(), prepared.target(),
                company.version().next()), previous.code(), previous.operatingName(), previous.description(), previous.logoUrl(),
                new Audit(previous.audit().createdAt(), previous.audit().createdBy(), now, command.context().actor()));
        return query(deadline, () -> storage.companies().transition(row, command.expectedVersion(), company.status()))
                .chain(() -> complete(command, key, row, now, deadline))
                .chain(() -> query(deadline, () -> command.operation() == CompanyLifecycle.Operation.ARCHIVE
                        ? storage.events().store(CompanyUpdated.archivedCandidate(ids.next(), row, command.context()))
                        : storage.events().store(CompanyLifecycleEvent.candidate(ids.next(), row, command.context(), command.operation()))))
                .replaceWith(row);
    }

    private Uni<Void> complete(CompanyLifecycle.Command command, CompletionKey key, CompanyRecord row,
            Instant now, LifecycleDeadline deadline) {
        if (key == null) return Uni.createFrom().voidItem();
        var signature = idempotency.fingerprints().sign(command);
        var completion = new CompletionRecord(key, signature.hash(), command.companyId(), null, command.expectedVersion(),
                (short) 200, idempotency.results().currentVersion(), idempotency.results().encode(row), now,
                command.context().actor(), signature.version(), signature.keyId());
        return query(deadline, () -> idempotency.completions().insert(completion)).replaceWithVoid();
    }

    private Uni<CompletionRecord> lookup(CompletionKey key, LifecycleDeadline deadline) {
        return key == null ? Uni.createFrom().nullItem() : query(deadline, () -> idempotency.completions().find(key));
    }

    private CompanyRecord replay(CompanyLifecycle.Command command, CompletionRecord stored) {
        if (stored.fingerprintVersion() == null || stored.fingerprintKeyId() == null || stored.resultStatus() != 200) {
            throw new IllegalStateException("historical-company-lifecycle-completion-invalid");
        }
        if (!idempotency.fingerprints().matches(command, stored.fingerprintVersion(), stored.fingerprintKeyId(), stored.requestHash())) {
            throw new CompanyLifecycleFailure(CompanyLifecycleFailure.Kind.IDEMPOTENCY_MISMATCH);
        }
        if (!command.companyId().equals(stored.companyId()) || !command.context().tenantId().equals(stored.key().tenantId())
                || !command.operation().namespace().equals(stored.key().operation())
                || !command.idempotencyKey().equals(stored.key().key())) {
            throw new IllegalStateException("historical-company-lifecycle-completion-invalid");
        }
        return idempotency.results().decode(stored);
    }

    private Uni<CompanyRecord> recover(CompanyLifecycle.Command command, CompletionKey key, LifecycleDeadline deadline,
            PersistenceFailure failure) {
        return database(false, deadline, () -> lookup(key, deadline).chain(stored -> {
            if (stored != null) return Uni.createFrom().item(replay(command, stored));
            var kind = failure.kind() == PersistenceFailure.Kind.STALE_VERSION
                    ? CompanyLifecycleFailure.Kind.STALE_VERSION : CompanyLifecycleFailure.Kind.UNAVAILABLE;
            return Uni.createFrom().failure(new CompanyLifecycleFailure(kind));
        }));
    }

    private boolean invalidWrite(Throwable failure) {
        return failure instanceof PersistenceFailure p
                && (p.kind() == PersistenceFailure.Kind.INVALID_DATA || p.kind() == PersistenceFailure.Kind.INVALID_REFERENCE);
    }

    private boolean recoverable(Throwable failure) {
        return failure instanceof PersistenceFailure p && p.transactionFinalized()
                && (p.kind() == PersistenceFailure.Kind.DUPLICATE || p.kind() == PersistenceFailure.Kind.STALE_VERSION
                        || p.kind() == PersistenceFailure.Kind.UNAVAILABLE);
    }

    private <T> Uni<T> query(LifecycleDeadline deadline, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> storage.atomic().checkpoint(deadline.remaining()).chain(work::get));
    }

    private <T> Uni<T> database(boolean write, LifecycleDeadline deadline, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> write ? storage.atomic().execute(deadline.remaining(), work)
                : storage.atomic().read(deadline.remaining(), work));
    }
}
