package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.ContactMutation;
import com.alexastudillo.companyregistry.application.site.ContactScope;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Reflection required by the independent historical codec; Domain remains framework-free. */
@RegisterForReflection(targets = {ContactMutation.View.class, ContactMutation.Deletion.class,
        ContactScope.class, RegistryData.ContactRecord.class, RegistryData.Audit.class})
final class ContactNativeMetadata {}
