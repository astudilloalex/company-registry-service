package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.*;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.*;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;
import com.alexastudillo.companyregistry.domain.model.*;
import io.smallrye.mutiny.Uni;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Sequential protected acceptance, with geographic I/O strictly outside
 * finalized database sessions.
 */
public final class MutateAddress {
    public record Storage(AtomicOperation atomic, CompanyRepository companies, SiteRepository sites,
            SiteMutationEvents events) {
    }

    public record Idempotency(CompletionRepository completions, AddressFingerprints fingerprints,
            AddressResults results) {
    }

    private record Prepared(SiteRecord site, AddressRecord previous, AddressInformation information, boolean changed,
            AddressMutation.Result replay) {
    }

    private record Stage(AddressMutation.Result result, AddressGeographyPolicy.Plan geography) {
    }

    private final Storage storage;
    private final Idempotency idempotency;
    private final AddressGeography geography;
    private final Identifiers ids;
    private final Clock clock;
    private final Duration waitBudget;

    public MutateAddress(Storage storage, Idempotency idempotency, AddressGeography geography, Identifiers ids,
            Clock clock, Duration waitBudget) {
        this.storage = storage;
        this.idempotency = idempotency;
        this.geography = geography;
        this.ids = ids;
        this.clock = clock;
        this.waitBudget = Duration.ofNanos(AddressDeadline.validate(waitBudget));
    }

    public Uni<AddressMutation.Result> execute(AddressMutation.Command command) {
        return Uni.createFrom().deferred(() -> {
            var deadline = new AddressDeadline(waitBudget);
            var key = command.idempotencyKey() == null ? null
                    : new CompletionKey(command.scope().tenantId(),
                            command.intent().operation().namespace(), command.idempotencyKey());
            return database(false, deadline, () -> lookup(key, deadline))
                    .chain(stored -> stored != null ? Uni.createFrom().item(replay(command, stored))
                            : begin(command, key, deadline))
                    .onFailure(this::invalidWrite).transform(_ -> new IllegalStateException("address-write-failed"))
                    .onFailure(this::recoverable)
                    .recoverWithUni(failure -> recover(command, key, deadline, (PersistenceFailure) failure))
                    .invoke(_ -> deadline.check());
        }).onFailure(failure -> failure instanceof PersistenceFailure p
                && (!p.transactionFinalized() || p.kind() == PersistenceFailure.Kind.UNAVAILABLE))
                .transform(_ -> new AddressFailure(AddressFailure.Kind.UNAVAILABLE));
    }

    private Uni<AddressMutation.Result> begin(AddressMutation.Command command, CompletionKey key,
            AddressDeadline deadline) {
        return attempt(command, key, null, deadline).chain(stage -> {
            if (stage.result() != null)
                return Uni.createFrom().item(stage.result());
            return observe(command, stage.geography(), deadline)
                    .chain(observation -> attempt(command, key, observation, deadline))
                    .map(Stage::result);
        });
    }

    private Uni<AddressGeography.Observation> observe(AddressMutation.Command command, AddressGeographyPolicy.Plan plan,
            AddressDeadline deadline) {
        return Uni.createFrom().deferred(() -> geography.observe(command.context(), plan)
                .ifNoItem().after(deadline.remaining())
                .failWith(() -> new ReferenceFailure(ReferenceFailure.Kind.TIMEOUT)))
                .onFailure(ReferenceFailure.class)
                .recoverWithItem(failure -> new AddressGeography.Observation(null, (failure).kind()));
    }

    private Uni<Stage> attempt(AddressMutation.Command command, CompletionKey key,
            AddressGeography.Observation observation,
            AddressDeadline deadline) {
        return database(true, deadline, () -> protect(command, key, deadline)
                .chain(prepared -> evaluateStage(command, key, prepared, observation, deadline)));
    }

    private Uni<Stage> evaluateStage(AddressMutation.Command command, CompletionKey key, Prepared prepared,
            AddressGeography.Observation observation, AddressDeadline deadline) {
        if (prepared.replay() != null) {
            return Uni.createFrom().item(new Stage(prepared.replay(), null));
        }
        var plan = geographyPlan(command, prepared);
        if (plan != null && plan.required()) {
            if (observation == null) return Uni.createFrom().item(new Stage(null, plan));
            eligible(observation);
        }
        return accept(command, key, prepared, deadline).map(result -> new Stage(result, null));
    }

    private static AddressGeographyPolicy.Plan geographyPlan(AddressMutation.Command command, Prepared prepared) {
        if (command.intent().operation() == AddressMutation.Operation.DELETE) {
            return null;
        }
        var previousText = prepared.previous() == null ? null : information(prepared.previous()).text();
        return AddressGeographyPolicy.plan(previousText, prepared.information().text());
    }

    private Uni<Prepared> protect(AddressMutation.Command command, CompletionKey key, AddressDeadline deadline) {
        var scope = command.scope();
        return query(deadline, () -> storage.companies().findForUpdate(scope.tenantId(), scope.companyId()))
                .chain(parent -> lookup(key, deadline).chain(stored -> {
                    if (stored != null) return Uni.createFrom().item(new Prepared(null, null, null, false, replay(command, stored)));
                    if (parent == null) throw new AddressFailure(AddressFailure.Kind.COMPANY_NOT_FOUND);
                    return resolveSite(command, parent, deadline);
                }));
    }

    private Uni<Prepared> resolveSite(AddressMutation.Command command, CompanyRecord parent, AddressDeadline deadline) {
        var scope = command.scope();
        return query(deadline, () -> storage.sites().findForUpdate(scope.tenantId(), scope.companyId(), scope.siteId()))
                .chain(site -> {
                    if (site == null) throw new AddressFailure(AddressFailure.Kind.SITE_NOT_FOUND);
                    return prepareTarget(command, parent, site, deadline);
                });
    }

    private Uni<Prepared> prepareTarget(AddressMutation.Command command, CompanyRecord parent, SiteRecord site,
            AddressDeadline deadline) {
        if (command.intent().operation() == AddressMutation.Operation.CREATE) {
            return Uni.createFrom().item(prepare(command, parent, site, null));
        }
        return query(deadline, () -> storage.sites().addressDetail(command.scope(), command.intent().addressId()))
                .map(address -> {
                    if (address == null) throw new AddressFailure(AddressFailure.Kind.ADDRESS_NOT_FOUND);
                    return prepare(command, parent, site, address.address());
                });
    }

    private static Prepared prepare(AddressMutation.Command command, CompanyRecord parent, SiteRecord site,
            AddressRecord previous) {
        if (site.site().version().value() != command.expectedVersion())
            throw new AddressFailure(AddressFailure.Kind.STALE_VERSION);
        if (parent.company().status() == CompanyStatus.ARCHIVED)
            throw new AddressFailure(AddressFailure.Kind.COMPANY_ARCHIVED);
        if (site.site().status() == SiteStatus.CLOSED)
            throw new AddressFailure(AddressFailure.Kind.SITE_CLOSED);
        return switch (command.intent().operation()) {
            case CREATE ->
                new Prepared(site, null, AddressInformationPolicy.create(command.intent().patch()), true, null);
            case DELETE -> new Prepared(site, previous, information(previous), true, null);
            case UPDATE -> {
                var decision = AddressInformationPolicy.decide(information(previous), command.intent().patch());
                yield new Prepared(site, previous, decision.information(), decision.changed(), null);
            }
        };
    }

    private Uni<AddressMutation.Result> accept(AddressMutation.Command command, CompletionKey key, Prepared prepared,
            AddressDeadline deadline) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!prepared.changed()) {
            var result = new AddressMutation.View(command.scope(), prepared.previous(),
                    prepared.site().site().version().value());
            return complete(command, key, result, now, deadline).replaceWith(result);
        }
        var site = revised(prepared.site(), command.context().actor(), now);
        var row = row(command, prepared, now);
        AddressMutation.Result result = command.intent().operation() == AddressMutation.Operation.DELETE
                ? new AddressMutation.Deletion(command.scope(), row.id(), site.site().version().value(), now,
                        command.context().actor())
                : new AddressMutation.View(command.scope(), row, site.site().version().value());
        return demote(command, prepared.information(), row.id(), now, deadline)
                .chain(() -> query(deadline, () -> prepared.previous() == null
                        ? storage.sites().insertAddress(command.scope().tenantId(), command.scope().companyId(), row)
                        : storage.sites().updateAddress(command.scope(), row)))
                .chain(() -> query(deadline, () -> storage.sites().update(site, command.expectedVersion())))
                .chain(() -> complete(command, key, result, now, deadline))
                .chain(() -> query(deadline,
                        () -> storage.events().store(SiteMutated.candidate(ids.next(), site, command.context()))))
                .replaceWith(result);
    }

    private Uni<Void> demote(AddressMutation.Command command, AddressInformation effective, UUID target, Instant now,
            AddressDeadline deadline) {
        if (!effective.isPrimary() || command.intent().operation() == AddressMutation.Operation.DELETE)
            return Uni.createFrom().voidItem();
        return query(deadline, () -> storage.sites().primaryAddress(command.scope(), effective.addressType()))
                .chain(primary -> {
                    var selected = primary == null ? null
                            : new AddressPrimaryPolicy.Selection(primary.id(), primary.type());
                    var decision = AddressPrimaryPolicy.decide(target, effective, selected);
                    if (decision.demoteAddressId() == null)
                        return Uni.createFrom().voidItem();
                    var demoted = new AddressRecord(primary.id(), primary.siteId(), primary.type(),
                            primary.countryCode(),
                            primary.administrativeDivisionCode(), primary.line1(), primary.line2(),
                            primary.houseNumber(), primary.postalCode(),
                            primary.latitude(), primary.longitude(), false,
                            new Audit(primary.audit().createdAt(), primary.audit().createdBy(), now,
                                    command.context().actor()),
                            null, null);
                    return query(deadline, () -> storage.sites().updateAddress(command.scope(), demoted))
                            .replaceWithVoid();
                });
    }

    private AddressRecord row(AddressMutation.Command command, Prepared prepared, Instant now) {
        var previous = prepared.previous();
        var effective = prepared.information();
        var text = effective.text();
        var actor = command.context().actor();
        boolean deleted = command.intent().operation() == AddressMutation.Operation.DELETE;
        return new AddressRecord(previous == null ? ids.next() : previous.id(), command.scope().siteId(),
                effective.addressType(),
                text.countryCode(), text.administrativeDivisionCode(), text.addressLine1(), text.addressLine2(),
                text.houseNumber(),
                text.postalCode(), effective.coordinates().latitude(), effective.coordinates().longitude(),
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

    public static AddressInformation information(AddressRecord row) {
        return new AddressInformation(row.type(),
                new AddressText(row.countryCode(), row.line1(), row.administrativeDivisionCode(),
                        row.line2(), row.houseNumber(), row.postalCode()),
                new AddressCoordinates(row.latitude(), row.longitude()), row.primary());
    }

    private Uni<Void> complete(AddressMutation.Command command, CompletionKey key, AddressMutation.Result result,
            Instant now,
            AddressDeadline deadline) {
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

    private Uni<CompletionRecord> lookup(CompletionKey key, AddressDeadline deadline) {
        return key == null ? Uni.createFrom().nullItem() : query(deadline, () -> idempotency.completions().find(key));
    }

    private AddressMutation.Result replay(AddressMutation.Command command, CompletionRecord stored) {
        if (stored.fingerprintVersion() == null || stored.fingerprintKeyId() == null)
            throw new IllegalStateException("historical-address-completion-invalid");
        if (!idempotency.fingerprints().matches(command, stored.fingerprintVersion(), stored.fingerprintKeyId(),
                stored.requestHash())) {
            throw new AddressFailure(AddressFailure.Kind.IDEMPOTENCY_MISMATCH);
        }
        if (!command.scope().tenantId().equals(stored.key().tenantId())
                || !command.scope().companyId().equals(stored.companyId())
                || !command.scope().siteId().equals(stored.siteId())
                || !command.intent().operation().namespace().equals(stored.key().operation())
                || !command.idempotencyKey().equals(stored.key().key())
                || !Long.valueOf(command.expectedVersion()).equals(stored.expectedVersion())) {
            throw new IllegalStateException("historical-address-completion-invalid");
        }
        return idempotency.results().decode(stored, command.intent().addressId());
    }

    private Uni<AddressMutation.Result> recover(AddressMutation.Command command, CompletionKey key,
            AddressDeadline deadline,
            PersistenceFailure failure) {
        return database(false, deadline, () -> lookup(key, deadline).map(stored -> {
            if (stored != null)
                return replay(command, stored);
            throw new AddressFailure(failure.kind() == PersistenceFailure.Kind.STALE_VERSION
                    ? AddressFailure.Kind.STALE_VERSION
                    : AddressFailure.Kind.UNAVAILABLE);
        }));
    }

    private static void eligible(AddressGeography.Observation observation) {
        if (observation.failure() != null)
            throw new AddressFailure(observation.failure() == ReferenceFailure.Kind.INVALID_CONTRACT
                    ? AddressFailure.Kind.DEPENDENCY_INVALID
                    : AddressFailure.Kind.UNAVAILABLE);
        if (observation.eligibility() != AddressGeographyPolicy.Eligibility.ELIGIBLE) {
            var kind = switch (observation.eligibility()) {
                case COUNTRY_NOT_FOUND -> AddressFailure.Kind.COUNTRY_NOT_FOUND;
                case COUNTRY_INACTIVE -> AddressFailure.Kind.COUNTRY_INACTIVE;
                case DIVISION_NOT_FOUND -> AddressFailure.Kind.DIVISION_NOT_FOUND;
                case DIVISION_INACTIVE -> AddressFailure.Kind.DIVISION_INACTIVE;
                case ELIGIBLE -> throw new IllegalStateException();
            };
            throw new AddressFailure(kind);
        }
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

    private <T> Uni<T> query(AddressDeadline deadline, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> storage.atomic().checkpoint(deadline.remaining()).chain(work::get));
    }

    private <T> Uni<T> database(boolean write, AddressDeadline deadline, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> write ? storage.atomic().execute(deadline.remaining(), work)
                : storage.atomic().read(deadline.remaining(), work));
    }
}
