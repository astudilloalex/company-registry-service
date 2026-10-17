package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.alexastudillo.companyregistry.application.company.PartyView;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import com.alexastudillo.companyregistry.domain.model.Company;
import com.alexastudillo.companyregistry.domain.model.AggregateVersion;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Metadata stays at the native boundary; core records remain framework-free. */
@RegisterForReflection(targets={CompanyRegistration.Result.class, RegistryData.CompanyRecord.class, RegistryData.Audit.class,
        Company.class, AggregateVersion.class, PartyView.class, PartyView.LegalEntity.class, PartyView.Identifier.class})
final class CompanyNativeMetadata {}
