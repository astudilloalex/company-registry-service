package com.alexastudillo.companyregistry.application.reference;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.References.CountryReference;
import io.smallrye.mutiny.Uni;

/** Resolves an alpha-2 code; null means confirmed absence, never dependency failure. */
public interface CountryReferences {
    Uni<CountryReference> find(OperationContext context, String alpha2Code);
}
