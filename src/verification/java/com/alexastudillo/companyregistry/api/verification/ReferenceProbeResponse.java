package com.alexastudillo.companyregistry.api.verification;

import java.util.UUID;

/** Minimal verification-variant DTO for external-client behavior. */
public record ReferenceProbeResponse(UUID id, String type, String state, UUID countryId, String code, boolean active) {}
