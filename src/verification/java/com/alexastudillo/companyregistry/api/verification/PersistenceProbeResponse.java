package com.alexastudillo.companyregistry.api.verification;

import java.util.UUID;

/** Public DTO of the verification variant, never a Domain or ORM entity. */
public record PersistenceProbeResponse(UUID companyId, UUID siteId, UUID eventId, boolean rolledBack) {}
