package com.alexastudillo.companyregistry.application.site;

public interface ContactFingerprints {
    record Signature(short version, String keyId, String hash) {}
    Signature sign(ContactMutation.Command command);
    boolean matches(ContactMutation.Command command, short version, String keyId, String hash);
}
