package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.company.CompanyLifecycle;
import com.alexastudillo.companyregistry.application.company.LifecycleFingerprints;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class ConfiguredLifecycleFingerprints implements LifecycleFingerprints {
    private final HmacKeys keys;
    @Inject public ConfiguredLifecycleFingerprints(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }
    public ConfiguredLifecycleFingerprints(HmacKeys keys) { this.keys = keys; }
    @Override public Signature sign(CompanyLifecycle.Command command) {
        return new Signature(LifecycleFingerprintV1.VERSION, keys.activeId(),
                LifecycleFingerprintV1.sign(command, keys.secret(keys.activeId())));
    }
    @Override public boolean matches(CompanyLifecycle.Command command, short version, String keyId, String hash) {
        if (version != LifecycleFingerprintV1.VERSION) throw new IllegalStateException("historical-fingerprint-policy-unavailable");
        return LifecycleFingerprintV1.matches(command, keys.secret(keyId), hash);
    }
}
