package com.alexastudillo.companyregistry.application.company;

import java.util.Map;

/** Historical result documents are decoded without a current aggregate or provider lookup. */
public interface AcceptedResults {
    short currentVersion();
    Map<String, Object> encode(CompanyRegistration.Result result);
    CompanyRegistration.Result decode(short version, Map<String, Object> document);
}
