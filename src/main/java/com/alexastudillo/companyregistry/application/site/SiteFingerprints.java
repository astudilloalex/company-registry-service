package com.alexastudillo.companyregistry.application.site;

/** Resolves the stored policy/key on each comparison, including after a losing transaction. */
public interface SiteFingerprints {
    record Signature(short version, String keyId, String hash) {}
    Signature sign(SiteRegistration.Command command);
    boolean matches(SiteRegistration.Command command, short version, String keyId, String hash);
}
