package com.alexastudillo.companyregistry.application.company;

/** Resolves the stored policy/key on each comparison, including after a losing transaction. */
public interface Fingerprints {
    record Signature(short version, String keyId, String hash) {}
    Signature sign(CompanyRegistration.Command command);
    boolean matches(CompanyRegistration.Command command, short version, String keyId, String hash);
}
