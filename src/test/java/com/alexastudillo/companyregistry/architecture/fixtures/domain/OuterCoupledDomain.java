package com.alexastudillo.companyregistry.architecture.fixtures.domain;

/** Deliberately invalid dependencies proving that address policies cannot import outer layers. */
public class OuterCoupledDomain {
    public com.alexastudillo.companyregistry.domain.model.AddressInformation value;
    public com.alexastudillo.companyregistry.application.site.SiteMutation application;
    public jakarta.persistence.EntityManager persistence;
    public jakarta.ws.rs.core.Response transport;
    public io.smallrye.mutiny.Uni<String> reactive;
}
