package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.SiteMutation;
import com.alexastudillo.companyregistry.application.site.SiteMutationFingerprints;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup @ApplicationScoped
public class ConfiguredSiteMutationFingerprints implements SiteMutationFingerprints {
    private final HmacKeys keys;
    @Inject public ConfiguredSiteMutationFingerprints(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }
    public ConfiguredSiteMutationFingerprints(HmacKeys keys) { this.keys = keys; }
    @Override public Signature sign(SiteMutation.Command command) {
        return new Signature(SiteMutationFingerprintV1.VERSION, keys.activeId(), SiteMutationFingerprintV1.sign(command, keys.secret(keys.activeId())));
    }
    @Override public boolean matches(SiteMutation.Command command, short version, String keyId, String hash) {
        if (version != SiteMutationFingerprintV1.VERSION) throw new IllegalStateException("historical-fingerprint-policy-unavailable");
        return SiteMutationFingerprintV1.matches(command, keys.secret(keyId), hash);
    }
}
