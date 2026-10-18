package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.CountryReferences;
import com.alexastudillo.companyregistry.application.reference.DivisionReferences;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;
import com.alexastudillo.companyregistry.domain.model.AddressGeographyPolicy;
import com.alexastudillo.companyregistry.domain.model.AddressGeographyPolicy.Eligibility;
import com.alexastudillo.companyregistry.domain.model.AddressGeographyPolicy.Plan;
import io.smallrye.mutiny.Uni;
import java.util.Objects;

/**
 * Records remote outcomes as observations, for use only after renewed local
 * protection.
 */
public final class AddressGeography {
    private final CountryReferences countries;
    private final DivisionReferences divisions;

    public AddressGeography(CountryReferences countries, DivisionReferences divisions) {
        this.countries = Objects.requireNonNull(countries);
        this.divisions = Objects.requireNonNull(divisions);
    }

    public record Observation(Eligibility eligibility, ReferenceFailure.Kind failure) {
        public Observation {
            if ((eligibility == null) == (failure == null))
                throw new IllegalArgumentException("geography-observation-invalid");
        }
    }

    public Uni<Observation> observe(OperationContext context, Plan plan) {
        if (!plan.required())
            return Uni.createFrom().item(new Observation(Eligibility.ELIGIBLE, null));
        return Uni.createFrom().deferred(() -> countries.find(context, plan.countryCode()))
                .chain(country -> {
                    var eligibility = AddressGeographyPolicy.country(country != null,
                            country != null && country.active());
                    if (eligibility != Eligibility.ELIGIBLE || plan.administrativeDivisionCode() == null) {
                        return Uni.createFrom().item(new Observation(eligibility, null));
                    }
                    return divisions.findInCountry(context, country, plan.administrativeDivisionCode())
                            .map(division -> new Observation(AddressGeographyPolicy.division(division != null,
                                    division != null && division.active()), null));
                })
                .onFailure(ReferenceFailure.class).recoverWithItem(failure -> new Observation(null, (failure).kind()));
    }
}
