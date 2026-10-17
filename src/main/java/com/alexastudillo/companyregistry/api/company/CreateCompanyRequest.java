package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.companyregistry.api.validation.CompanyJsonBody;
import com.alexastudillo.companyregistry.api.validation.StrictStringDeserializer;
import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.alexastudillo.companyregistry.application.context.OperationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@CompanyJsonBody
public record CreateCompanyRequest(
        @NotNull(message = "party-id-required") UUID partyId,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotNull(message = "code-required") String code,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotNull(message = "operating-name-required") String operatingName,
        @JsonDeserialize(using = StrictStringDeserializer.class) String description,
        @JsonDeserialize(using = StrictStringDeserializer.class) String logoUrl) {
    public CompanyRegistration.Command command(OperationContext context, String key) {
        return new CompanyRegistration.Command(context, key, partyId, code, operatingName, description, logoUrl);
    }
}
