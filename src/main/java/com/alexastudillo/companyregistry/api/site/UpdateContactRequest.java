package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.domain.model.ContactInformationPatch;

public record UpdateContactRequest(ContactInformationPatch patch) {}
