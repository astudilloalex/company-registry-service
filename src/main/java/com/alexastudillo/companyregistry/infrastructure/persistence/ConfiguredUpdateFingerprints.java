package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.CompanyUpdate;
import com.alexastudillo.companyregistry.application.company.UpdateFingerprints;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class ConfiguredUpdateFingerprints implements UpdateFingerprints {
    private final HmacKeys keys;
    @Inject public ConfiguredUpdateFingerprints(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }
    public ConfiguredUpdateFingerprints(HmacKeys keys) { this.keys = keys; }
    @Override public Signature sign(CompanyUpdate.Command command) {
        return new Signature(UpdateFingerprintV1.VERSION, keys.activeId(),
                UpdateFingerprintV1.sign(command, keys.secret(keys.activeId())));
    }
    @Override public boolean matches(CompanyUpdate.Command command, short version, String keyId, String hash) {
        if (version != UpdateFingerprintV1.VERSION) throw new IllegalStateException("historical-fingerprint-policy-unavailable");
        return UpdateFingerprintV1.matches(command, keys.secret(keyId), hash);
    }
}
