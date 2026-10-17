package com.alexastudillo.companyregistry.application.company;

/** Historical update policies consume original structured intent, including presence. */
public interface UpdateFingerprints {
    record Signature(short version, String keyId, String hash) {}
    Signature sign(CompanyUpdate.Command command);
    boolean matches(CompanyUpdate.Command command, short version, String keyId, String hash);
}
