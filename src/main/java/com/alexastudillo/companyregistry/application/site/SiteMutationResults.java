package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord;
import java.util.Map;

public interface SiteMutationResults {
    short currentVersion();
    Map<String, Object> encode(SiteMutation.Result result);
    SiteMutation.Result decode(CompletionRecord stored);
}
