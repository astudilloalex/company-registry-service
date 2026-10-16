package com.alexastudillo.companyregistry.architecture.fixtures.domain;

/** Deliberately invalid, test-only input to the architecture rule. */
@jakarta.enterprise.context.ApplicationScoped
public class FrameworkCoupledDomain {
    public io.smallrye.mutiny.Uni<String> value;
}
