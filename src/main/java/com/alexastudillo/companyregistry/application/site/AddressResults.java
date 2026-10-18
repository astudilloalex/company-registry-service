package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord;
import java.util.Map;

public interface AddressResults {
    short currentVersion();
    Map<String, Object> encode(AddressMutation.Operation operation, AddressMutation.Result result);
    AddressMutation.Result decode(CompletionRecord stored, java.util.UUID expectedAddressId);
}
