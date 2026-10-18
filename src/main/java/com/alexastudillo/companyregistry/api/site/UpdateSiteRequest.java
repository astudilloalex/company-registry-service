package com.alexastudillo.companyregistry.api.site;

import com.alexastudillo.companyregistry.domain.model.SiteInformationPatch;

/** Raw partial intent; canonicalization belongs to the selected acceptance policy. */
public record UpdateSiteRequest(SiteInformationPatch patch) {}
