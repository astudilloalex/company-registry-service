package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

public interface PartyReads {
    /** Null indicates tenant-scoped absence. */
    Uni<PartyView> find(OperationContext context, UUID partyId);
    default Uni<PartyView> findForRegistration(OperationContext context, UUID partyId) { return find(context, partyId); }
    /** Same order as input, bounded concurrency and a shared deadline. */
    Uni<List<PartyView>> findAll(OperationContext context, List<UUID> partyIds);
}
