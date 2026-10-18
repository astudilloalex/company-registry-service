package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.SiteRegistration;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.Site;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Native serialization belongs at the infrastructure boundary. */
@RegisterForReflection(targets={SiteRegistration.Result.class, RegistryData.SiteRecord.class, Site.class})
final class SiteNativeMetadata {}
