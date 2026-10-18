package com.alexastudillo.companyregistry.application.site;

/** Resolves the stored policy/key on each comparison, including after a losing transaction. */
public interface SiteMutationFingerprints {
    record Signature(short version, String keyId, String hash) {}
    Signature sign(SiteMutation.Command command);
    boolean matches(SiteMutation.Command command, short version, String keyId, String hash);
}
