package com.alexastudillo.companyregistry.infrastructure.reference;

import com.alexastudillo.companyregistry.application.company.*;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;
import com.alexastudillo.companyregistry.application.reference.References;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;

@ApplicationScoped
public class PartyReadAdapter implements PartyReads {
    private final PartyClient client;
    private final Duration deadline;
    private final int concurrency;

    @Inject
    public PartyReadAdapter(
            @RestClient PartyClient client,
            @ConfigProperty(name = "company-registry.enrichment.deadline", defaultValue = "30s") Duration deadline,
            @ConfigProperty(name = "company-registry.enrichment.concurrency", defaultValue = "4") int concurrency) {
        this.client = client;
        this.deadline = deadline;
        this.concurrency = concurrency;
        validateConfiguration();
    }

    public PartyReadAdapter(PartyClient client) {
        this(client, Duration.ofSeconds(30), 4);
    }

    private void validateConfiguration() {
        if (deadline.isNegative() || deadline.isZero() || concurrency < 1 || concurrency > 200)
            throw new IllegalArgumentException("enrichment-limits-invalid");
    }

    @Override
    public Uni<PartyView> find(OperationContext context, UUID partyId) {
        return bounded(read(context, partyId));
    }

    @Override
    public Uni<PartyView> findForRegistration(OperationContext context, UUID partyId) {
        return bounded(read(context, partyId, true));
    }

    @Override
    public Uni<List<PartyView>> findAll(OperationContext context, List<UUID> ids) {
        if (ids.isEmpty())
            return Uni.createFrom().item(List.of());
        return bounded(Uni.createFrom().deferred(() -> {
            var results = new PartyView[ids.size()];
            return Multi.createFrom().iterable(IntStream.range(0, ids.size()).boxed().toList())
                    .onItem().transformToUni(index -> read(context, ids.get(index)).invoke(value -> {
                        if (value == null)
                            throw RemoteContracts.invalid();
                        results[index] = value;
                    })).merge(concurrency).collect().asList().map(ignored -> List.of(results));
        }));
    }

    private <T> Uni<T> bounded(Uni<T> operation) {
        return operation.ifNoItem().after(deadline).failWith(() -> new ReferenceFailure(ReferenceFailure.Kind.TIMEOUT));
    }

    private Uni<PartyView> read(OperationContext context, UUID id) {
        return read(context, id, false);
    }

    private Uni<PartyView> read(OperationContext context, UUID id, boolean registration) {
        return client.detail(id, context.tenantId().toString(), context.actor(), context.processId())
                .onFailure(RemoteContracts::clientFailure).transform(RemoteContracts::processing)
                .map(RemoteContracts::data).chain(value -> {
                    if (value == null)
                        return Uni.createFrom().nullItem();
                    var view = detail(id, value);
                    // Natural-person incompatibility remains visible to registration policy.
                    if (!"LEGAL_ENTITY".equals(view.type()))
                        return Uni.createFrom().item(view);
                    if (registration && !com.alexastudillo.companyregistry.domain.model.RegistrationPolicy
                            .eligible(view.type(), view.recordStatus()))
                        return Uni.createFrom().item(view);
                    return Uni.createFrom()
                            .deferred(
                                    () -> pages(context, id, null, new HashSet<>(), new HashSet<>(), new ArrayList<>()))
                            .map(identifiers -> new PartyView(view.partyId(), view.type(), view.displayName(),
                                    view.recordStatus(), view.version(),
                                    view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy(),
                                    view.legalEntityDetails(), identifiers));
                });
    }

    private Uni<List<PartyView.Identifier>> pages(OperationContext context, UUID id, String cursor, Set<String> cursors,
            Set<UUID> ids, List<PartyView.Identifier> collected) {
        return client.identifiers(id, context.tenantId().toString(), context.actor(), context.processId(), 200, cursor)
                .onFailure(RemoteContracts::clientFailure).transform(RemoteContracts::processing)
                .map(PartyReadAdapter::extractPage)
                .chain(page -> advance(context, id, page, cursors, ids, collected));
    }

    private Uni<List<PartyView.Identifier>> advance(OperationContext context, UUID id, PartyReadModels.Page page,
            Set<String> cursors, Set<UUID> ids, List<PartyView.Identifier> collected) {
        collectIdentifiers(id, page, ids, collected);
        if (page.nextCursor() == null)
            return Uni.createFrom().item(List.copyOf(collected));
        if (page.nextCursor().isBlank() || !cursors.add(page.nextCursor()))
            throw RemoteContracts.invalid();
        return pages(context, id, page.nextCursor(), cursors, ids, collected);
    }

    private static PartyReadModels.Page extractPage(RestResponse<PartyReadModels.Page> response) {
        try (response) {
            int status = response.getStatus();
            if (status >= 500 || status == 429)
                throw new ReferenceFailure(ReferenceFailure.Kind.UNAVAILABLE);
            if (status != 200)
                throw RemoteContracts.invalid();
            var page = response.getEntity();
            if (isInvalidPage(page))
                throw RemoteContracts.invalid();
            return page;
        }
    }

    private static boolean isInvalidPage(PartyReadModels.Page page) {
        return page == null || !Integer.valueOf(200).equals(page.status())
                || !"successful".equals(page.code()) || page.data() == null
                || page.numberOfElements() == null || page.numberOfElements() != page.data().size()
                || page.data().size() > 200;
    }

    private static void collectIdentifiers(UUID id, PartyReadModels.Page page, Set<UUID> ids,
            List<PartyView.Identifier> collected) {
        for (var item : page.data()) {
            var identifier = identifier(id, item);
            if (!ids.add(identifier.identifierId()))
                throw RemoteContracts.invalid();
            collected.add(identifier);
        }
    }

    private static PartyView detail(UUID id, PartyReadModels.Detail value) {
        if (!id.equals(value.partyId()) || value.type() == null || value.recordStatus() == null
                || value.version() == null || value.version() < 0
                || value.displayName() == null || value.createdAt() == null || value.updatedAt() == null
                || value.createdBy() == null || value.updatedBy() == null)
            throw RemoteContracts.invalid();
        try {
            References.PartyKind.valueOf(value.type());
            References.PartyState.valueOf(value.recordStatus());
        } catch (IllegalArgumentException _) {
            throw RemoteContracts.invalid();
        }
        PartyView.LegalEntity legal = null;
        if ("LEGAL_ENTITY".equals(value.type())) {
            var l = value.legalEntityDetails();
            if (l == null || l.legalName() == null || l.incorporationCountryCode() == null)
                throw RemoteContracts.invalid();
            legal = new PartyView.LegalEntity(l.legalName(), l.tradeName(), l.legalFormCode(),
                    l.incorporationCountryCode(), l.incorporatedOn(), l.dissolvedOn());
        }
        return new PartyView(id, value.type(), value.displayName(), value.recordStatus(), value.version(),
                value.createdAt(), value.updatedAt(), value.createdBy(), value.updatedBy(), legal, List.of());
    }

    private static PartyView.Identifier identifier(UUID id, PartyReadModels.Identifier value) {
        if (value == null || !id.equals(value.partyId()) || value.identifierId() == null
                || value.identifierSchemeId() == null
                || value.schemeCode() == null || value.maskedValue() == null || value.status() == null
                || value.isPrimary() == null
                || value.version() == null || value.version() < 0 || value.createdAt() == null
                || value.updatedAt() == null
                || !Set.of("PENDING_VERIFICATION", "VERIFIED", "REJECTED", "EXPIRED", "REVOKED")
                        .contains(value.status()))
            throw RemoteContracts.invalid();
        return new PartyView.Identifier(value.identifierId(), id, value.identifierSchemeId(), value.schemeCode(),
                value.maskedValue(), value.status(), value.isPrimary(),
                value.issuerCode(), value.issuedOn(), value.expiresOn(), value.verifiedAt(), value.verifiedBy(),
                value.version(), value.createdAt(), value.updatedAt());
    }
}
