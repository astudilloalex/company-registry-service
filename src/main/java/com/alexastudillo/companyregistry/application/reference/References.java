package com.alexastudillo.companyregistry.application.reference;

import java.util.Objects;
import java.util.UUID;

/** Minimal reference projections; no legal details or provider transport DTOs cross this boundary. */
public final class References {
    private References() {}
    public enum PartyKind { NATURAL_PERSON, LEGAL_ENTITY }
    public enum PartyState { DRAFT, ACTIVE, INACTIVE, ARCHIVED }
    public enum GeographicState { DRAFT, ACTIVE, DEPRECATED, RETIRED }
    public record PartyReference(UUID id, PartyKind kind, PartyState state) {
        public PartyReference { Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(state); }
        public boolean legalEntity() { return kind == PartyKind.LEGAL_ENTITY; }
        public boolean active() { return state == PartyState.ACTIVE; }
    }
    public record CountryReference(UUID id, String alpha2Code, GeographicState state) {
        public CountryReference { Objects.requireNonNull(id); Objects.requireNonNull(alpha2Code); Objects.requireNonNull(state); }
        public boolean active() { return state == GeographicState.ACTIVE; }
    }
    public record DivisionReference(UUID id, UUID countryId, String canonicalCode, GeographicState state, GeographicState countryState) {
        public DivisionReference { Objects.requireNonNull(id); Objects.requireNonNull(countryId); Objects.requireNonNull(canonicalCode); Objects.requireNonNull(state); Objects.requireNonNull(countryState); }
        public boolean active() { return state == GeographicState.ACTIVE && countryState == GeographicState.ACTIVE; }
    }
}
