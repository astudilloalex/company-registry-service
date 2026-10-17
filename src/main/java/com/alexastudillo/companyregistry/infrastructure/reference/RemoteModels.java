package com.alexastudillo.companyregistry.infrastructure.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.UUID;

/** Provider transport projections; unknown provider details are deliberately ignored, never copied inward. */
public final class RemoteModels {
    private RemoteModels() {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Envelope<T>(Integer status, String code, T data) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Party(UUID partyId, String type, String recordStatus) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Country(UUID id, String alpha2Code, String status) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @RegisterForReflection
    public record Division(UUID id, UUID countryId, String canonicalCode, String status) {}
}
