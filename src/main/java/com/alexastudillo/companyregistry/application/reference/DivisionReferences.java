package com.alexastudillo.companyregistry.application.reference;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.References.DivisionReference;
import io.smallrye.mutiny.Uni;

/** Resolves country alpha-2 then a division owned by that country's identity. */
public interface DivisionReferences {
    Uni<DivisionReference> find(OperationContext context, String alpha2Code, String canonicalCode);
}
