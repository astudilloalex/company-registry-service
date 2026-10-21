package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord;
import java.util.Map;

public interface ContactResults {
    short currentVersion();
    Map<String, Object> encode(ContactMutation.Operation operation, ContactMutation.Result result);
    ContactMutation.Result decode(CompletionRecord stored, java.util.UUID expectedContactId);
}
