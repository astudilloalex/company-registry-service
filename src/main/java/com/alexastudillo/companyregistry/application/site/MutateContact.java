package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

/** One local acceptance protected in Company then Site order. */
public final class MutateContact {
    public record Storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteMutationEvents events) {
    }

    public record Idempotency(CompletionRepository completions, ContactFingerprints fingerprints,
            ContactResults results) {
    }

    private record Prepared(SiteRecord site, ContactRecord previous, ContactInformation information, boolean changed,
            ContactMutation.Result replay) {
    }

    private final Storage storage;
    private final Idempotency idempotency;
    private final Identifiers ids;
    private final Clock clock;
    private final Duration waitBudget;

    public MutateContact(Storage storage, Idempotency idempotency, Identifiers ids,
            Clock clock, Duration waitBudget) {
        this.storage = storage;
        this.idempotency = idempotency;
        this.ids = ids;
        this.clock = clock;
        this.waitBudget = Duration.ofNanos(ContactDeadline.validate(waitBudget));
    }

    public Uni<ContactMutation.Result> execute(ContactMutation.Command command) {
        return Uni.createFrom().deferred(() -> {
            var deadline = new ContactDeadline(waitBudget);
            var key = command.idempotencyKey() == null ? null
                    : new CompletionKey(command.scope().tenantId(),
                            command.intent().operation().namespace(), command.idempotencyKey());
            return database(false, deadline, () -> lookup(key, deadline))
                    .chain(stored -> stored != null ? Uni.createFrom().item(replay(command, stored))
                            : begin(command, key, deadline))
                    .onFailure(this::invalidWrite).transform(_ -> new IllegalStateException("contact-write-failed"))
                    .onFailure(this::recoverable)
                    .recoverWithUni(failure -> recover(command, key, deadline, (PersistenceFailure) failure))
                    .invoke(_ -> deadline.check());
        }).onFailure(failure -> failure instanceof PersistenceFailure p
                && (!p.transactionFinalized() || p.kind() == PersistenceFailure.Kind.UNAVAILABLE))
                .transform(_ -> new ContactFailure(ContactFailure.Kind.UNAVAILABLE));
    }

    private Uni<ContactMutation.Result> begin(ContactMutation.Command command, CompletionKey key,
            ContactDeadline deadline) {
        // Canonical supplied fields fail before reading current ownership or state.
        var intention = ContactIntentPolicyV1.canonical(command.intent().patch());
        var candidate = new ContactMutation.Command(command.context(), command.scope(), command.expectedVersion(),
                command.idempotencyKey(), new ContactMutation.Intent(command.intent().operation(),
                        command.intent().contactId(), intention.patch()));
        return database(true, deadline, () -> protect(candidate, command, key, deadline)
                .chain(prepared -> prepared.replay() != null ? Uni.createFrom().item(prepared.replay())
                        : accept(candidate, key, prepared, deadline)));
    }

    private Uni<Prepared> protect(ContactMutation.Command command, ContactMutation.Command original,
            CompletionKey key, ContactDeadline deadline) {
        var scope = command.scope();
        return query(deadline, () -> storage.companies().findForUpdate(scope.tenantId(), scope.companyId()))
                .chain(parent -> lookup(key, deadline).chain(stored -> {
                    // A completion found after waiting selects its historical policy over the original raw input.
                    if (stored != null) return Uni.createFrom().item(new Prepared(null, null, null, false, replay(original, stored)));
                    if (parent == null) throw new ContactFailure(ContactFailure.Kind.COMPANY_NOT_FOUND);
                    return resolveSite(command, parent, deadline);
                }));
    }

    private Uni<Prepared> resolveSite(ContactMutation.Command command, CompanyRecord parent, ContactDeadline deadline) {
        var scope = command.scope();
        return query(deadline, () -> storage.sites().findForUpdate(scope.tenantId(), scope.companyId(), scope.siteId()))
                .chain(site -> {
                    if (site == null) throw new ContactFailure(ContactFailure.Kind.SITE_NOT_FOUND);
                    return prepareTarget(command, parent, site, deadline);
                });
    }

    private Uni<Prepared> prepareTarget(ContactMutation.Command command, CompanyRecord parent, SiteRecord site,
            ContactDeadline deadline) {
        if (command.intent().operation() == ContactMutation.Operation.CREATE) {
            return Uni.createFrom().item(prepare(command, parent, site, null));
        }
        return query(deadline, () -> storage.sites().contactDetail(command.scope(), command.intent().contactId()))
                .map(contact -> {
                    if (contact == null) throw new ContactFailure(ContactFailure.Kind.CONTACT_NOT_FOUND);
                    return prepare(command, parent, site, contact.contact());
                });
    }

    private static Prepared prepare(ContactMutation.Command command, CompanyRecord parent, SiteRecord site,
            ContactRecord previous) {
        if (site.site().version().value() != command.expectedVersion())
            throw new ContactFailure(ContactFailure.Kind.STALE_VERSION);
        if (parent.company().status() == CompanyStatus.ARCHIVED)
            throw new ContactFailure(ContactFailure.Kind.COMPANY_ARCHIVED);
        if (site.site().status() == SiteStatus.CLOSED)
            throw new ContactFailure(ContactFailure.Kind.SITE_CLOSED);
        return switch (command.intent().operation()) {
            case CREATE ->
                new Prepared(site, null, ContactInformationPolicy.create(command.intent().patch()), true, null);
            case DELETE -> new Prepared(site, previous, information(previous), true, null);
            case UPDATE -> {
                var decision = ContactInformationPolicy.decide(information(previous), command.intent().patch());
                yield new Prepared(site, previous, decision.information(), decision.changed(), null);
            }
        };
    }

    private Uni<ContactMutation.Result> accept(ContactMutation.Command command, CompletionKey key, Prepared prepared,
            ContactDeadline deadline) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!prepared.changed()) {
            var result = new ContactMutation.View(command.scope(), prepared.previous(),
                    prepared.site().site().version().value());
            return complete(command, key, result, now, deadline).replaceWith(result);
        }
        var site = revised(prepared.site(), command.context().actor(), now);
        var row = row(command, prepared, now);
        ContactMutation.Result result = command.intent().operation() == ContactMutation.Operation.DELETE
                ? new ContactMutation.Deletion(command.scope(), row.id(), site.site().version().value(), now,
                        command.context().actor())
                : new ContactMutation.View(command.scope(), row, site.site().version().value());
        return duplicateCheck(command, prepared.information(), row.id(), deadline)
                .chain(() -> demote(command, prepared.information(), row.id(), now, deadline))
                .chain(() -> query(deadline, () -> prepared.previous() == null
                        ? storage.sites().insertContact(command.scope().tenantId(), command.scope().companyId(), row)
                        : storage.sites().updateContact(command.scope(), row)))
                .chain(() -> query(deadline, () -> storage.sites().update(site, command.expectedVersion())))
                .chain(() -> complete(command, key, result, now, deadline))
                .chain(() -> query(deadline,
                        () -> storage.events().store(SiteMutated.candidate(ids.next(), site, command.context()))))
                .replaceWith(result);
    }

    private Uni<Void> demote(ContactMutation.Command command, ContactInformation effective, UUID target, Instant now,
            ContactDeadline deadline) {
        if (!effective.isPrimary() || command.intent().operation() == ContactMutation.Operation.DELETE)
            return Uni.createFrom().voidItem();
        return query(deadline, () -> storage.sites().primaryContact(command.scope(), effective.contactType()))
                .chain(primary -> {
                    var selected = primary == null ? null
                            : new ContactPrimaryPolicy.Selection(primary.id(), primary.type());
                    var decision = ContactPrimaryPolicy.decide(target, effective, selected);
                    if (decision.demoteContactId() == null)
                        return Uni.createFrom().voidItem();
                    var demoted = new ContactRecord(primary.id(), primary.siteId(), primary.type(),
                            primary.value(), primary.label(), false,
                            new Audit(primary.audit().createdAt(), primary.audit().createdBy(), now,
                                    command.context().actor()), null, null);
                    return query(deadline, () -> storage.sites().updateContact(command.scope(), demoted))
                            .replaceWithVoid();
                });
    }

    private ContactRecord row(ContactMutation.Command command, Prepared prepared, Instant now) {
        var previous = prepared.previous();
        var effective = prepared.information();
        var actor = command.context().actor();
        boolean deleted = command.intent().operation() == ContactMutation.Operation.DELETE;
        return new ContactRecord(previous == null ? ids.next() : previous.id(), command.scope().siteId(),
                effective.contactType(),
                effective.value(), effective.label(),
                !deleted && effective.isPrimary(),
                previous == null ? new Audit(now, actor, now, actor)
                        : new Audit(previous.audit().createdAt(), previous.audit().createdBy(), now, actor),
                deleted ? now : null, deleted ? actor : null);
    }

    private static SiteRecord revised(SiteRecord previous, String actor, Instant now) {
        var site = previous.site();
        return new SiteRecord(
                new Site(site.id(), site.tenantId(), site.companyId(), site.type(), site.status(),
                        site.version().next()),
                previous.code(), previous.name(),
                new Audit(previous.audit().createdAt(), previous.audit().createdBy(), now, actor));
    }

    public static ContactInformation information(ContactRecord row) {
        return new ContactInformation(row.type(), row.value(), row.label(), row.primary());
    }

    private Uni<Void> duplicateCheck(ContactMutation.Command command, ContactInformation information, UUID target,
            ContactDeadline deadline) {
        if (command.intent().operation() == ContactMutation.Operation.DELETE) return Uni.createFrom().voidItem();
        return query(deadline, () -> storage.sites().contactValueOccupiedByOther(command.scope(),
                information.contactType(), information.value(), target)).invoke(occupied -> {
                    if (Boolean.TRUE.equals(occupied)) throw new ContactFailure(ContactFailure.Kind.CONTACT_VALUE);
                }).replaceWithVoid();
    }

    private Uni<Void> complete(ContactMutation.Command command, CompletionKey key, ContactMutation.Result result,
            Instant now,
            ContactDeadline deadline) {
        if (key == null)
            return Uni.createFrom().voidItem();
        var signature = idempotency.fingerprints().sign(command);
        var stored = new CompletionRecord(key, signature.hash(), command.scope().companyId(), command.scope().siteId(),
                command.expectedVersion(),
                command.intent().operation().resultStatus(), idempotency.results().currentVersion(),
                idempotency.results().encode(command.intent().operation(), result), now, command.context().actor(),
                signature.version(), signature.keyId());
        return query(deadline, () -> idempotency.completions().insert(stored)).replaceWithVoid();
    }

    private Uni<CompletionRecord> lookup(CompletionKey key, ContactDeadline deadline) {
        return key == null ? Uni.createFrom().nullItem() : query(deadline, () -> idempotency.completions().find(key));
    }

    private ContactMutation.Result replay(ContactMutation.Command command, CompletionRecord stored) {
        if (stored.fingerprintVersion() == null || stored.fingerprintKeyId() == null)
            throw new IllegalStateException("historical-contact-completion-invalid");
        if (!idempotency.fingerprints().matches(command, stored.fingerprintVersion(), stored.fingerprintKeyId(),
                stored.requestHash())) {
            throw new ContactFailure(ContactFailure.Kind.IDEMPOTENCY_MISMATCH);
        }
        if (!command.scope().tenantId().equals(stored.key().tenantId())
                || !command.scope().companyId().equals(stored.companyId())
                || !command.scope().siteId().equals(stored.siteId())
                || !command.intent().operation().namespace().equals(stored.key().operation())
                || !command.idempotencyKey().equals(stored.key().key())
                || !Long.valueOf(command.expectedVersion()).equals(stored.expectedVersion())) {
            throw new IllegalStateException("historical-contact-completion-invalid");
        }
        return idempotency.results().decode(stored, command.intent().contactId());
    }

    private Uni<ContactMutation.Result> recover(ContactMutation.Command command, CompletionKey key,
            ContactDeadline deadline,
            PersistenceFailure failure) {
        if (failure.uniqueness() == PersistenceFailure.Uniqueness.CONTACT_VALUE) {
            return Uni.createFrom().failure(new ContactFailure(ContactFailure.Kind.CONTACT_VALUE));
        }
        return database(false, deadline, () -> lookup(key, deadline).map(stored -> {
            if (stored != null)
                return replay(command, stored);
            throw new ContactFailure(failure.kind() == PersistenceFailure.Kind.STALE_VERSION
                    ? ContactFailure.Kind.STALE_VERSION
                    : ContactFailure.Kind.UNAVAILABLE);
        }));
    }

    private boolean invalidWrite(Throwable failure) {
        return failure instanceof PersistenceFailure p && (p.kind() == PersistenceFailure.Kind.INVALID_DATA
                || p.kind() == PersistenceFailure.Kind.INVALID_REFERENCE);
    }

    private boolean recoverable(Throwable failure) {
        return failure instanceof PersistenceFailure p && p.transactionFinalized()
                && (p.kind() == PersistenceFailure.Kind.DUPLICATE || p.kind() == PersistenceFailure.Kind.UNAVAILABLE
                        || p.kind() == PersistenceFailure.Kind.STALE_VERSION);
    }

    private <T> Uni<T> query(ContactDeadline deadline, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> storage.atomic().checkpoint(deadline.remaining()).chain(work::get));
    }

    private <T> Uni<T> database(boolean write, ContactDeadline deadline, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> write ? storage.atomic().execute(deadline.remaining(), work)
                : storage.atomic().read(deadline.remaining(), work));
    }
}
