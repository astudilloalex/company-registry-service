package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.application.site.AddressMutation;
import com.alexastudillo.companyregistry.domain.model.AddressType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddressResponse(UUID addressId, UUID siteId, long siteVersion, AddressType addressType, String countryCode,
        String addressLine1, boolean isPrimary, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy,
        String administrativeDivisionCode, String addressLine2, String houseNumber, String postalCode,
        BigDecimal latitude, BigDecimal longitude) {
    public static AddressResponse from(AddressMutation.View view) {
        var row = view.address();
        return new AddressResponse(row.id(), row.siteId(), view.siteVersion(), row.type(), row.countryCode(), row.line1(), row.primary(),
                row.audit().createdAt(), row.audit().createdBy(), row.audit().updatedAt(), row.audit().updatedBy(),
                row.administrativeDivisionCode(), row.line2(), row.houseNumber(), row.postalCode(), number(row.latitude()), number(row.longitude()));
    }

    private static BigDecimal number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
