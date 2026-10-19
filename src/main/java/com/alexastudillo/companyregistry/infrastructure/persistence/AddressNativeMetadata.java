package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.AddressMutation;
import com.alexastudillo.companyregistry.application.site.AddressScope;
import com.alexastudillo.companyregistry.application.persistence.RegistryData;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Reflection required by the independent historical codec; Domain remains framework-free. */
@RegisterForReflection(targets = {AddressMutation.View.class, AddressMutation.Deletion.class,
        AddressScope.class, RegistryData.AddressRecord.class, RegistryData.Audit.class})
final class AddressNativeMetadata {}
