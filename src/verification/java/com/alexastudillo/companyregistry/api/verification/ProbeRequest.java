package com.alexastudillo.companyregistry.api.verification;

import com.alexastudillo.companyregistry.api.validation.BusinessTextDeserializer;
import com.alexastudillo.companyregistry.api.validation.CompanyJsonBody;
import com.alexastudillo.companyregistry.api.validation.TextLength;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;

@CompanyJsonBody
public record ProbeRequest(
        @JsonDeserialize(using = BusinessTextDeserializer.class)
        @NotBlank(message = "name-required") @TextLength(max = 10, message = "name-too-long") String name) {}
