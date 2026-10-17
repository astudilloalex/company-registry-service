package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.CompanyRegistration;
import com.alexastudillo.companyregistry.application.company.Fingerprints;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup @ApplicationScoped
public class ConfiguredFingerprints implements Fingerprints {
    private final HmacKeys keys;
    @Inject public ConfiguredFingerprints(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }
    public ConfiguredFingerprints(HmacKeys keys) { this.keys = keys; }
    @Override public Signature sign(CompanyRegistration.Command command) {
        return new Signature(CreationFingerprintV1.VERSION, keys.activeId(), CreationFingerprintV1.sign(command, keys.secret(keys.activeId())));
    }
    @Override public boolean matches(CompanyRegistration.Command command, short version, String keyId, String hash) {
        if (version != CreationFingerprintV1.VERSION) throw new IllegalStateException("historical-fingerprint-policy-unavailable");
        return CreationFingerprintV1.matches(command, keys.secret(keyId), hash);
    }
}
