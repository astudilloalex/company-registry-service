package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.domain.model.AddressInformationPatch;

public record UpdateAddressRequest(AddressInformationPatch patch) {}
