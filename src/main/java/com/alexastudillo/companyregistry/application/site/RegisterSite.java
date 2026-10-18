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
public final class RegisterSite {
    public record Storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteCreationEvents events) {
    }

    public record Idempotency(CompletionRepository completions, SiteFingerprints fingerprints, SiteResults results) {
    }

    private final Storage storage;
    private final Idempotency idempotency;
    private final Identifiers ids;
    private final Clock clock;
    private final Duration waitBudget;

    public RegisterSite(Storage storage, Idempotency idempotency, Identifiers ids, Clock clock, Duration waitBudget) {
        this.storage = storage;
        this.idempotency = idempotency;
        this.ids = ids;
        this.clock = clock;
        if (waitBudget == null) {
            throw new IllegalArgumentException("site-registration-budget-invalid");
        }
        try {
            if (waitBudget.toNanos() <= 0) {
                throw new IllegalArgumentException("site-registration-budget-invalid");
            }
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException("site-registration-budget-invalid");
        }
        this.waitBudget = waitBudget;
    }

    public Uni<SiteRegistration.Result> execute(SiteRegistration.Command command) {
        return Uni.createFrom().deferred(() -> {
            var budget = new DatabaseBudget(waitBudget);
            var key = new CompletionKey(command.context().tenantId(), SiteRegistration.OPERATION,
                    command.idempotencyKey());
            return database(false, budget, () -> query(budget, () -> idempotency.completions().find(key)))
                    .chain(stored -> stored != null ? Uni.createFrom().item(replay(command, stored))
                            : persist(command, key, budget));
        }).onFailure(
                failure -> failure instanceof com.alexastudillo.companyregistry.application.company.CompanyFailure company
                        && company
                                .kind() == com.alexastudillo.companyregistry.application.company.CompanyFailure.Kind.UNAVAILABLE)
                .transform(_ -> new SiteFailure(SiteFailure.Kind.UNAVAILABLE));
    }

    private Uni<SiteRegistration.Result> persist(SiteRegistration.Command command, CompletionKey key,
            DatabaseBudget budget) {
        return database(true, budget,
                () -> query(budget, () -> storage.companies().findForUpdate(key.tenantId(), command.companyId()))
                        .chain(parent -> query(budget, () -> idempotency.completions().find(key)).chain(stored -> {
                            if (stored != null) {
                                return Uni.createFrom().item(replay(command, stored));
                            }
                            validateParent(parent);
                            return ensureSiteCodesAvailable(key, command, budget)
                                    .chain(() -> insertSiteAndCompletion(command, key, budget));
                        })))
                .onFailure(this::invalidWrite)
                .transform(_ -> new IllegalStateException("site-registration-write-failed"))
                .onFailure(this::recoverable)
                .recoverWithUni(failure -> recover(command, key, budget, (PersistenceFailure) failure));
    }

    private void validateParent(CompanyRecord parent) {
        if (parent == null) {
            throw new SiteFailure(SiteFailure.Kind.COMPANY_NOT_FOUND);
        }
        if (!SiteRegistrationPolicy.admits(parent.company().status())) {
            throw new SiteFailure(SiteFailure.Kind.COMPANY_ARCHIVED);
        }
    }

    private Uni<Void> ensureSiteCodesAvailable(CompletionKey key, SiteRegistration.Command command,
            DatabaseBudget budget) {
        var input = command.effective();
        return query(budget,
                () -> storage.sites().codeOccupied(key.tenantId(), command.companyId(), input.code()))
                .chain(occupied -> {
                    if (Boolean.TRUE.equals(occupied)) {
                        throw new SiteFailure(SiteFailure.Kind.CODE_OCCUPIED);
                    }
                    return input.siteType() == SiteType.HEADQUARTERS
                            ? query(budget,
                                    () -> storage.sites().headquartersOccupied(key.tenantId(),
                                            command.companyId()))
                            : Uni.createFrom().item(false);
                }).chain(occupied -> {
                    if (Boolean.TRUE.equals(occupied)) {
                        throw new SiteFailure(SiteFailure.Kind.HEADQUARTERS_OCCUPIED);
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<SiteRegistration.Result> insertSiteAndCompletion(SiteRegistration.Command command, CompletionKey key,
            DatabaseBudget budget) {
        var candidate = SiteRegistration.candidate(command, clock, ids);
        var result = new SiteRegistration.Result(candidate, false);
        var signature = idempotency.fingerprints().sign(command);
        var completion = new CompletionRecord(key, signature.hash(),
                command.companyId(), candidate.site().id(),
                null, (short) 201, idempotency.results().currentVersion(),
                idempotency.results().encode(result),
                candidate.audit().createdAt(), command.context().actor(),
                signature.version(), signature.keyId());
        var event = SiteCreated.candidate(ids.next(), candidate, command.context());
        return query(budget, () -> storage.sites().insert(candidate))
                .chain(() -> query(budget,
                        () -> idempotency.completions().insert(completion)))
                .chain(() -> query(budget, () -> storage.events().store(event)))
                .replaceWith(result);
    }

    private Uni<SiteRegistration.Result> recover(SiteRegistration.Command command, CompletionKey key,
            DatabaseBudget budget, PersistenceFailure failure) {
        return database(false, budget, () -> query(budget, () -> idempotency.completions().find(key)).chain(stored -> {
            if (stored != null)
                return Uni.createFrom().item(replay(command, stored));
            var kind = switch (failure.uniqueness()) {
                case SITE_CODE -> SiteFailure.Kind.CODE_OCCUPIED;
                case SITE_HEADQUARTERS -> SiteFailure.Kind.HEADQUARTERS_OCCUPIED;
                default -> SiteFailure.Kind.UNAVAILABLE;
            };
            return Uni.createFrom().failure(new SiteFailure(kind));
        }));
    }

    private SiteRegistration.Result replay(SiteRegistration.Command command, CompletionRecord stored) {
        if (stored.fingerprintVersion() == null || stored.fingerprintKeyId() == null || stored.resultStatus() != 201)
            throw new IllegalStateException("historical-site-completion-invalid");
        if (!idempotency.fingerprints().matches(command, stored.fingerprintVersion(), stored.fingerprintKeyId(),
                stored.requestHash()))
            throw new SiteFailure(SiteFailure.Kind.IDEMPOTENCY_MISMATCH);
        if (!command.companyId().equals(stored.companyId())
                || !command.context().tenantId().equals(stored.key().tenantId())
                || !SiteRegistration.OPERATION.equals(stored.key().operation())
                || !command.idempotencyKey().equals(stored.key().key()))
            throw new IllegalStateException("historical-site-completion-invalid");
        return idempotency.results().decode(stored);
    }

    private boolean invalidWrite(Throwable failure) {
        return failure instanceof PersistenceFailure p && (p.kind() == PersistenceFailure.Kind.INVALID_DATA
                || p.kind() == PersistenceFailure.Kind.INVALID_REFERENCE);
    }

    private boolean recoverable(Throwable failure) {
        return failure instanceof PersistenceFailure p && (p.kind() == PersistenceFailure.Kind.DUPLICATE
                || p.kind() == PersistenceFailure.Kind.UNAVAILABLE);
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
