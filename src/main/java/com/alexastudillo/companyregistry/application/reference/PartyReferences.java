package com.alexastudillo.companyregistry.application.reference;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.References.PartyReference;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

/** Null means absent in the tenant; type/activity remain explicit policy inputs. */
public interface PartyReferences {
    Uni<PartyReference> find(OperationContext context, UUID partyId);
}
