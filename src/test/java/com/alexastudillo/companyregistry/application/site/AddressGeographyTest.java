package com.alexastudillo.companyregistry.application.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.alexastudillo.companyregistry.application.reference.CountryReferences;
import com.alexastudillo.companyregistry.application.reference.DivisionReferences;
import com.alexastudillo.companyregistry.application.reference.ReferenceFailure;
import com.alexastudillo.companyregistry.application.reference.References.CountryReference;
import com.alexastudillo.companyregistry.application.reference.References.DivisionReference;
import com.alexastudillo.companyregistry.application.reference.References.GeographicState;
import com.alexastudillo.companyregistry.domain.model.AddressGeographyPolicy;
import com.alexastudillo.companyregistry.domain.model.AddressGeographyPolicy.Eligibility;
import com.alexastudillo.companyregistry.domain.model.AddressText;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AddressGeographyTest {
    private static final OperationContext CONTEXT = new OperationContext(UUID.randomUUID(), "Actor", UUID.randomUUID().toString());
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void plansOnlyNewAssignmentsIncludingRetainedDivisionAfterCountryChange() {
        var previous = text("EC", "EC-P");
        assertFalse(AddressGeographyPolicy.plan(previous, text(" ec ", "ec-p")).required());
        assertFalse(AddressGeographyPolicy.plan(previous, text("EC", null)).required());
        assertTrue(AddressGeographyPolicy.plan(null, previous).required());
        assertTrue(AddressGeographyPolicy.plan(previous, text("EC", "EC-G")).required());
        var retained = AddressGeographyPolicy.plan(previous, text("CO", "EC-P"));
        assertTrue(retained.required());
        assertEquals("EC-P", retained.administrativeDivisionCode());
        assertEquals(null, AddressGeographyPolicy.plan(previous, text("CO", null)).administrativeDivisionCode());
    }

    @Test
    void unchangedAndRemovedReferencesNeverCallPorts() {
        var countries = mock(CountryReferences.class);
        var divisions = mock(DivisionReferences.class);
        var geography = new AddressGeography(countries, divisions);
        var previous = text("EC", "EC-P");
        for (var effective : new AddressText[] {previous, text("EC", null)}) {
            var result = geography.observe(CONTEXT, AddressGeographyPolicy.plan(previous, effective)).await().atMost(TIMEOUT);
            assertEquals(Eligibility.ELIGIBLE, result.eligibility());
        }
        verify(countries, never()).find(CONTEXT, "EC");
        verify(divisions, never()).find(CONTEXT, "EC", "EC-P");
    }

    @Test
    void missingOrInactiveCountryShortCircuitsDivision() {
        var countries = mock(CountryReferences.class);
        var divisions = mock(DivisionReferences.class);
        var geography = new AddressGeography(countries, divisions);
        var plan = AddressGeographyPolicy.plan(null, text("EC", "EC-P"));
        when(countries.find(CONTEXT, "EC")).thenReturn(Uni.createFrom().nullItem());
        assertEquals(Eligibility.COUNTRY_NOT_FOUND, geography.observe(CONTEXT, plan).await().atMost(TIMEOUT).eligibility());
        for (var state : new GeographicState[] {GeographicState.DRAFT, GeographicState.DEPRECATED, GeographicState.RETIRED}) {
            var country = new CountryReference(UUID.randomUUID(), "EC", state);
            when(countries.find(CONTEXT, "EC")).thenReturn(Uni.createFrom().item(country));
            assertEquals(Eligibility.COUNTRY_INACTIVE, geography.observe(CONTEXT, plan).await().atMost(TIMEOUT).eligibility());
            verify(divisions, never()).findInCountry(CONTEXT, country, "EC-P");
        }
    }

    @Test
    void distinguishesDivisionAbsenceAndEveryInactiveState() {
        var countries = mock(CountryReferences.class);
        var divisions = mock(DivisionReferences.class);
        var country = new CountryReference(UUID.randomUUID(), "EC", GeographicState.ACTIVE);
        when(countries.find(CONTEXT, "EC")).thenReturn(Uni.createFrom().item(country));
        var geography = new AddressGeography(countries, divisions);
        var plan = AddressGeographyPolicy.plan(null, text("EC", "EC-P"));
        when(divisions.findInCountry(CONTEXT, country, "EC-P")).thenReturn(Uni.createFrom().nullItem());
        assertEquals(Eligibility.DIVISION_NOT_FOUND, geography.observe(CONTEXT, plan).await().atMost(TIMEOUT).eligibility());
        for (var state : GeographicState.values()) {
            var division = new DivisionReference(UUID.randomUUID(), country.id(), "EC-P", state, country.state());
            when(divisions.findInCountry(CONTEXT, country, "EC-P")).thenReturn(Uni.createFrom().item(division));
            assertEquals(state == GeographicState.ACTIVE ? Eligibility.ELIGIBLE : Eligibility.DIVISION_INACTIVE,
                    geography.observe(CONTEXT, plan).await().atMost(TIMEOUT).eligibility());
        }
    }

    @Test
    void remoteFailuresRemainObservationsForLocalPrecedence() {
        var countries = mock(CountryReferences.class);
        var geography = new AddressGeography(countries, mock(DivisionReferences.class));
        var plan = AddressGeographyPolicy.plan(null, text("EC", null));
        for (var kind : ReferenceFailure.Kind.values()) {
            when(countries.find(CONTEXT, "EC")).thenReturn(Uni.createFrom().failure(new ReferenceFailure(kind)));
            assertEquals(kind, geography.observe(CONTEXT, plan).await().atMost(TIMEOUT).failure());
        }
    }

    private static AddressText text(String country, String division) {
        return new AddressText(country, "A", division, null, null, null);
    }
}
