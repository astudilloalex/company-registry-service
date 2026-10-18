package com.alexastudillo.companyregistry.application.company;

/** Historical Company lifecycle signatures bind original empty intent. */
public interface LifecycleFingerprints {
    record Signature(short version, String keyId, String hash) {}
    Signature sign(CompanyLifecycle.Command command);
    boolean matches(CompanyLifecycle.Command command, short version, String keyId, String hash);
}
