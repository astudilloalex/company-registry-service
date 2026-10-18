package com.alexastudillo.companyregistry.application.site;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord;
import java.util.Map;

public interface SiteResults {
    short currentVersion();
    Map<String, Object> encode(SiteRegistration.Result result);
    SiteRegistration.Result decode(CompletionRecord stored);
}
