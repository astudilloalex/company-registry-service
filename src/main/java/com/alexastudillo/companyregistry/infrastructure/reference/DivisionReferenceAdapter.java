package com.alexastudillo.companyregistry.infrastructure.reference;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.*;
import com.alexastudillo.companyregistry.application.reference.References.*;
import com.alexastudillo.companyregistry.domain.text.BusinessText;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Uses the confirmed country-qualified provider contract and preserves both
 * activity decisions.
 */
@ApplicationScoped
public class DivisionReferenceAdapter implements DivisionReferences {
    private final CountryReferences countries;
    private final GeographicClient client;

    @Inject
    public DivisionReferenceAdapter(CountryReferences countries, @RestClient GeographicClient client) {
        this.countries = countries;
        this.client = client;
    }

    @Override
    public Uni<DivisionReference> find(OperationContext context, String alpha2Code, String canonicalCode) {
        var code = BusinessText.required(canonicalCode, 64);
        return countries.find(context, alpha2Code).chain(country -> {
            if (country == null)
                return Uni.createFrom().nullItem();
            return client.division(country.id(), code, context.actor(), context.processId())
                    .onFailure(RemoteContracts::clientFailure).transform(RemoteContracts::processing)
                    .map(RemoteContracts::data).map(value -> {
                        if (value == null)
                            return null;
                        if (value.id() == null || !country.id().equals(value.countryId())
                                || !code.equals(value.canonicalCode()) || value.status() == null)
                            throw RemoteContracts.invalid();
                        try {
                            return new DivisionReference(value.id(), value.countryId(), value.canonicalCode(),
                                    GeographicState.valueOf(value.status()), country.state());
                        } catch (IllegalArgumentException _) {
                            throw RemoteContracts.invalid();
                        }
                    });
        });
    }
}
