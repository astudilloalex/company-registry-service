package com.alexastudillo.companyregistry.infrastructure.reference;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.PartyReferences;
import com.alexastudillo.companyregistry.application.reference.References.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Keeps only Party identity/type/state; existence, type and activity remain
 * separate policy decisions.
 */
@ApplicationScoped
public class PartyReferenceAdapter implements PartyReferences {
    private final PartyClient client;

    @Inject
    public PartyReferenceAdapter(@RestClient PartyClient client) {
        this.client = client;
    }

    @Override
    public Uni<PartyReference> find(OperationContext context, UUID partyId) {
        return client.find(partyId, context.tenantId().toString(), context.actor(), context.processId())
                .onFailure(RemoteContracts::clientFailure).transform(RemoteContracts::processing)
                .map(RemoteContracts::data).map(value -> {
                    if (value == null)
                        return null;
                    if (!partyId.equals(value.partyId()) || value.type() == null || value.recordStatus() == null)
                        throw RemoteContracts.invalid();
                    try {
                        return new PartyReference(value.partyId(), PartyKind.valueOf(value.type()),
                                PartyState.valueOf(value.recordStatus()));
                    } catch (IllegalArgumentException _) {
                        throw RemoteContracts.invalid();
                    }
                });
    }
}
