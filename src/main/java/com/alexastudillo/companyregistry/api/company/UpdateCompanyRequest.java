package com.alexastudillo.companyregistry.api.company;

import com.alexastudillo.companyregistry.api.validation.CompanyJsonBody;
import com.alexastudillo.companyregistry.domain.model.CompanyInformationPatch;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@CompanyJsonBody
@JsonDeserialize(using = UpdateCompanyRequestReader.class)
public record UpdateCompanyRequest(CompanyInformationPatch patch) {}
