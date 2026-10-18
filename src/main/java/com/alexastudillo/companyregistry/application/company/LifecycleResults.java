package com.alexastudillo.companyregistry.application.company;

import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompanyRecord;
import com.alexastudillo.companyregistry.application.persistence.RegistryData.CompletionRecord;
import java.util.Map;

public interface LifecycleResults {
    short currentVersion();
    Map<String, Object> encode(CompanyRecord result);
    CompanyRecord decode(CompletionRecord stored);
}
