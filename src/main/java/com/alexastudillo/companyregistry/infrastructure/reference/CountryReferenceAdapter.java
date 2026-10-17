package com.alexastudillo.companyregistry.infrastructure.reference;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.CountryReferences;
import com.alexastudillo.companyregistry.application.reference.References.*;
import com.alexastudillo.companyregistry.domain.text.BusinessText;
import com.alexastudillo.companyregistry.domain.text.InvalidText;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Validates country response identity/code/status before projecting it inward.
 */
@ApplicationScoped
public class CountryReferenceAdapter implements CountryReferences {
    private final GeographicClient client;

    @Inject
    public CountryReferenceAdapter(@RestClient GeographicClient client) {
        this.client = client;
    }

    @Override
    public Uni<CountryReference> find(OperationContext context, String alpha2Code) {
        var code = BusinessText.required(alpha2Code, 2);
        if (!code.matches("[A-Z]{2}"))
            return Uni.createFrom().failure(new InvalidText("country-code-invalid"));
        return client.country(code, context.actor(), context.processId())
                .onFailure(RemoteContracts::clientFailure).transform(RemoteContracts::processing)
                .map(RemoteContracts::data).map(value -> {
                    if (value == null)
                        return null;
                    if (value.id() == null || !code.equals(value.alpha2Code()) || value.status() == null)
                        throw RemoteContracts.invalid();
                    try {
                        return new CountryReference(value.id(), value.alpha2Code(),
                                GeographicState.valueOf(value.status()));
                    } catch (IllegalArgumentException _) {
                        throw RemoteContracts.invalid();
                    }
                });
    }
}
