package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

/** One protected local acceptance, with durable recovery only after the failed session closes. */
public final class UpdateCompany {
    public record Storage(AtomicOperation atomic, CompanyRepository companies, UpdateEvents events) {}
    public record Idempotency(CompletionRepository completions, UpdateFingerprints fingerprints, UpdateResults results) {}
    private final Storage storage;
    private final Idempotency idempotency;
    private final Identifiers ids;
    private final Clock clock;
    private final Duration waitBudget;
    public UpdateCompany(Storage storage, Idempotency idempotency, Identifiers ids, Clock clock, Duration waitBudget) {
        this.storage=storage;this.idempotency=idempotency;this.ids=ids;this.clock=clock;
        if(waitBudget == null) throw new IllegalArgumentException("update-budget-invalid");
        try {if(waitBudget.toNanos() <= 0) throw new IllegalArgumentException("update-budget-invalid");} catch(ArithmeticException _) {throw new IllegalArgumentException("update-budget-invalid");}
        this.waitBudget=waitBudget;
    }
    public Uni<CompanyRecord> execute(CompanyUpdate.Command command) {
        return Uni.createFrom().deferred(()->{
            var budget=new DatabaseBudget(waitBudget);
            var key=command.idempotencyKey()==null?null:new CompletionKey(command.context().tenantId(),CompanyUpdate.OPERATION,command.idempotencyKey());
            return (key==null?Uni.createFrom().<CompletionRecord>nullItem():database(false,budget,()->query(budget,()->idempotency.completions().find(key))))
                    .chain(stored->stored!=null?Uni.createFrom().item(replay(command,stored)):persist(command,key,budget));
        });
    }
    private Uni<CompanyRecord> persist(CompanyUpdate.Command command, CompletionKey key, DatabaseBudget budget) {
        return database(true, budget, () -> query(budget, () -> storage.companies().findForUpdate(command.context().tenantId(), command.companyId()))
                .chain(current -> findCompletion(key, budget)
                        .chain(stored -> applyUpdate(command, key, budget, current, stored))))
                .onFailure(this::recoverable)
                .recoverWithUni(failure -> recover(command, key, budget, (PersistenceFailure) failure))
                .onFailure(this::isInvalidPersistence)
                .transform(_ -> new IllegalStateException("update-write-failed"));
    }

    private Uni<CompletionRecord> findCompletion(CompletionKey key, DatabaseBudget budget) {
        if (key == null) return Uni.createFrom().nullItem();
        return query(budget, () -> idempotency.completions().find(key));
    }

    private Uni<CompanyRecord> applyUpdate(
            CompanyUpdate.Command command,
            CompletionKey key,
            DatabaseBudget budget,
            CompanyRecord current,
            CompletionRecord stored) {
        if (stored != null) return Uni.createFrom().item(replay(command, stored));
        validateCurrent(current, command.expectedVersion());
        var decision = CompanyInformationPolicy.decide(current.company().status(),
                new CompanyInformation(current.code(), current.operatingName(), current.description(), current.logoUrl()), command.patch());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var accepted = applyDecision(current, decision, now, command.context().actor());
        return executeWrites(command, key, budget, accepted, decision.changed(), now);
    }

    private void validateCurrent(CompanyRecord current, long expectedVersion) {
        if (current == null) throw new CompanyFailure(CompanyFailure.Kind.NOT_FOUND);
        if (current.company().version().value() != expectedVersion) throw new CompanyFailure(CompanyFailure.Kind.VERSION_MISMATCH);
        if (!CompanyInformationPolicy.editable(current.company().status())) throw new CompanyFailure(CompanyFailure.Kind.CONFLICT);
    }

    private CompanyRecord applyDecision(CompanyRecord current, CompanyInformationPolicy.Decision decision, Instant now, String actor) {
        if (!decision.changed()) return current;
        var value = decision.information();
        return new CompanyRecord(new Company(current.company().id(), current.company().tenantId(), current.company().partyId(),
                current.company().status(), new AggregateVersion(Math.incrementExact(current.company().version().value()))),
                value.code(), value.operatingName(), value.description(), value.logoUrl(),
                new Audit(current.audit().createdAt(), current.audit().createdBy(), now, actor));
    }

    private Uni<CompanyRecord> executeWrites(
            CompanyUpdate.Command command,
            CompletionKey key,
            DatabaseBudget budget,
            CompanyRecord accepted,
            boolean changed,
            Instant now) {
        var write = changed
                ? query(budget, () -> storage.companies().update(accepted, command.expectedVersion()))
                : Uni.createFrom().item(accepted);
        if (key != null) {
            write = write.chain(() -> insertCompletion(command, key, budget, accepted, now));
        }
        if (changed) {
            write = write.chain(() -> storeEvent(command, budget, accepted));
        }
        return write;
    }

    private Uni<CompanyRecord> insertCompletion(
            CompanyUpdate.Command command,
            CompletionKey key,
            DatabaseBudget budget,
            CompanyRecord accepted,
            Instant now) {
        var signature = idempotency.fingerprints().sign(command);
        var completion = new CompletionRecord(key, signature.hash(), command.companyId(), null, command.expectedVersion(), (short) 200,
                idempotency.results().currentVersion(), idempotency.results().encode(accepted), now, command.context().actor(), signature.version(), signature.keyId());
        return query(budget, () -> idempotency.completions().insert(completion)).replaceWith(accepted);
    }

    private Uni<CompanyRecord> storeEvent(
            CompanyUpdate.Command command,
            DatabaseBudget budget,
            CompanyRecord accepted) {
        var event = CompanyUpdated.candidate(ids.next(), accepted, command.context());
        return query(budget, () -> storage.events().store(event)).replaceWith(accepted);
    }

    private boolean isInvalidPersistence(Throwable failure) {
        return failure instanceof PersistenceFailure p
                && (p.kind() == PersistenceFailure.Kind.INVALID_DATA || p.kind() == PersistenceFailure.Kind.INVALID_REFERENCE);
    }
    private boolean recoverable(Throwable f) {
        return f instanceof PersistenceFailure p && (p.kind()==PersistenceFailure.Kind.UNAVAILABLE
                || (p.kind()==PersistenceFailure.Kind.DUPLICATE && (p.uniqueness()==PersistenceFailure.Uniqueness.COMPANY_CODE
                || p.uniqueness()==PersistenceFailure.Uniqueness.COMPLETION_KEY)));
    }
    private Uni<CompanyRecord> recover(CompanyUpdate.Command command,CompletionKey key,DatabaseBudget budget,PersistenceFailure failure) {
        if(key==null) return Uni.createFrom().failure(new CompanyFailure(failure.uniqueness()==PersistenceFailure.Uniqueness.COMPANY_CODE
                ?CompanyFailure.Kind.CONFLICT:CompanyFailure.Kind.UNAVAILABLE));
        return database(false,budget,()->query(budget,()->idempotency.completions().find(key)).chain(stored->{
            if(stored!=null) return Uni.createFrom().item(replay(command,stored));
            return Uni.createFrom().failure(new CompanyFailure(failure.uniqueness()==PersistenceFailure.Uniqueness.COMPANY_CODE
                    ?CompanyFailure.Kind.CONFLICT:CompanyFailure.Kind.UNAVAILABLE));
        }));
    }
    private CompanyRecord replay(CompanyUpdate.Command command,CompletionRecord stored) {
        if(stored.fingerprintVersion()==null || stored.fingerprintKeyId()==null) throw new IllegalStateException("historical-completion-invalid");
        if(!idempotency.fingerprints().matches(command,stored.fingerprintVersion(),stored.fingerprintKeyId(),stored.requestHash()))
            throw new CompanyFailure(CompanyFailure.Kind.CONFLICT);
        var result=idempotency.results().decode(stored);
        if(!command.companyId().equals(stored.companyId()) || !command.context().tenantId().equals(stored.key().tenantId())
                || !Long.valueOf(command.expectedVersion()).equals(stored.expectedVersion())) throw new IllegalStateException("historical-completion-invalid");
        return result;
    }
    private <T> Uni<T> query(DatabaseBudget budget,Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(()->storage.atomic().checkpoint(budget.remaining()).chain(operation::get));
    }
    private <T> Uni<T> database(boolean write,DatabaseBudget budget,Supplier<Uni<T>> operation) {
        return Uni.createFrom().deferred(()->{
            budget.start();var remaining=budget.remaining();
            return (write?storage.atomic().execute(remaining,operation):storage.atomic().read(remaining,operation)).eventually(budget::pause);
        });
    }
}
