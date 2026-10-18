package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.RegistrationPolicy;
import io.smallrye.mutiny.Uni;
import java.time.Clock;

/** Local session boundaries close before enrichment and before durable loser recovery. */
public final class RegisterCompany {
    public record Storage(AtomicOperation atomic, CompanyRepository companies, CreationEvents events) {}
    public record Idempotency(CompletionRepository completions, Fingerprints fingerprints, AcceptedResults results) {}

    private final AtomicOperation atomic;
    private final CompanyRepository companies;
    private final CompletionRepository completions;
    private final PartyReads parties;
    private final Fingerprints fingerprints;
    private final AcceptedResults results;
    private final CreationEvents events;
    private final Identifiers ids;
    private final Clock clock;
    private final java.time.Duration waitBudget;
    public RegisterCompany(Storage storage, Idempotency idempotency, PartyReads parties, Identifiers ids, Clock clock) {
        this(storage, idempotency, parties, ids, clock, java.time.Duration.ofSeconds(10));
    }
    public RegisterCompany(Storage storage, Idempotency idempotency, PartyReads parties, Identifiers ids, Clock clock, java.time.Duration waitBudget) {
        this.atomic = storage.atomic();
        this.companies = storage.companies();
        this.events = storage.events();
        this.completions = idempotency.completions();
        this.fingerprints = idempotency.fingerprints();
        this.results = idempotency.results();
        this.parties = parties;
        this.ids = ids;
        this.clock = clock;
        if(waitBudget.isNegative() || waitBudget.isZero()) throw new IllegalArgumentException("registration-budget-invalid");
        this.waitBudget = waitBudget;
    }
    public Uni<CompanyRegistration.Result> execute(CompanyRegistration.Command command) {
        return Uni.createFrom().deferred(()->execute(command,new DatabaseBudget(waitBudget)));
    }
    private Uni<CompanyRegistration.Result> execute(CompanyRegistration.Command command, DatabaseBudget budget) {
        var key = new CompletionKey(command.context().tenantId(), CompanyRegistration.OPERATION, command.idempotencyKey());
        return database(false, budget, () -> query(budget, () -> completions.find(key))).chain(stored -> {
            if (stored != null) return Uni.createFrom().item(replay(command, stored));
            var input = command.effective();
            return parties.findForRegistration(command.context(), input.partyId())
                    .chain(party -> persist(command, key, party, budget));
        });
    }

    private Uni<CompanyRegistration.Result> persist(
            CompanyRegistration.Command command,
            CompletionKey key,
            PartyView party,
            DatabaseBudget budget) {
        validateParty(party);
        var candidate = CompanyRegistration.candidate(command, clock, ids);
        var result = new CompanyRegistration.Result(candidate, party);
        var signature = fingerprints.sign(command);
        var completion = new CompletionRecord(key, signature.hash(), candidate.company().id(), null, null, (short) 201, results.currentVersion(),
                results.encode(result), candidate.audit().createdAt(), command.context().actor(), signature.version(), signature.keyId());
        var event = CompanyCreated.candidate(ids.next(), candidate, command.context());
        return database(true, budget, () -> query(budget, () -> completions.find(key)).chain(second -> {
            if (second != null) return Uni.createFrom().item(replay(command, second));
            return query(budget, () -> companies.insert(candidate))
                    .chain(() -> query(budget, () -> completions.insert(completion)))
                    .chain(() -> query(budget, () -> events.store(event)))
                    .replaceWith(result);
        })).onFailure(this::isInvalidPersistence)
                .transform(failure -> new IllegalStateException("registration-write-failed"))
                .onFailure(this::isRecoverablePersistence)
                .recoverWithUni(failure -> recover(command, key, budget));
    }

    private void validateParty(PartyView party) {
        if (party == null) throw new CompanyFailure(CompanyFailure.Kind.REFERENCE_NOT_FOUND);
        if (!RegistrationPolicy.eligible(party.type(), party.recordStatus())) throw new CompanyFailure(CompanyFailure.Kind.INVALID_REFERENCE);
    }

    private boolean isInvalidPersistence(Throwable failure) {
        return failure instanceof PersistenceFailure p
                && (p.kind() == PersistenceFailure.Kind.INVALID_DATA || p.kind() == PersistenceFailure.Kind.INVALID_REFERENCE);
    }

    private boolean isRecoverablePersistence(Throwable failure) {
        return failure instanceof PersistenceFailure p
                && p.transactionFinalized()
                && (p.kind() == PersistenceFailure.Kind.DUPLICATE || p.kind() == PersistenceFailure.Kind.UNAVAILABLE);
    }
    private Uni<CompanyRegistration.Result> recover(CompanyRegistration.Command command,CompletionKey key,DatabaseBudget budget) {
        return database(false,budget,()->query(budget,()->completions.find(key)).chain(stored->{
            if(stored!=null) return Uni.createFrom().item(replay(command,stored));
            var input=command.effective();
            return query(budget,()->companies.occupied(command.context().tenantId(),input.code(),input.partyId())).chain(occupied->Uni.createFrom().failure(
                    new CompanyFailure(Boolean.TRUE.equals(occupied) ? CompanyFailure.Kind.CONFLICT : CompanyFailure.Kind.UNAVAILABLE)));
        }));
    }
    private <T> Uni<T> query(DatabaseBudget budget,java.util.function.Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(()->atomic.checkpoint(budget.remaining()).chain(operation::get));
    }
    private <T> Uni<T> database(boolean write,DatabaseBudget budget,java.util.function.Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(()->{
            budget.start();var remaining=budget.remaining();
            return (write?atomic.execute(remaining,operation):atomic.read(remaining,operation)).eventually(budget::pause);
        });
    }
    private CompanyRegistration.Result replay(CompanyRegistration.Command command,CompletionRecord stored) {
        if(stored.fingerprintVersion()==null || stored.fingerprintKeyId()==null || stored.resultStatus()!=201) throw new IllegalStateException("historical-completion-invalid");
        if(!fingerprints.matches(command,stored.fingerprintVersion(),stored.fingerprintKeyId(),stored.requestHash())) throw new CompanyFailure(CompanyFailure.Kind.CONFLICT);
        var result=results.decode(stored.snapshotVersion(),stored.snapshot());
        if(!stored.companyId().equals(result.company().company().id()) || !stored.key().tenantId().equals(result.company().company().tenantId())) throw new IllegalStateException("historical-completion-invalid");
        return result;
    }
}
