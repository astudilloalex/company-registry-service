package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.application.site.AddressMutation;
import java.time.Instant;
import java.util.UUID;

public record AddressDeletionResponse(UUID addressId, UUID siteId, long siteVersion, Instant deletedAt, String deletedBy) {
    public static AddressDeletionResponse from(AddressMutation.Deletion result) {
        return new AddressDeletionResponse(result.addressId(), result.scope().siteId(), result.siteVersion(), result.deletedAt(), result.deletedBy());
    }
}
