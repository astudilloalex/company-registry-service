package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.SiteRegistration;
import com.alexastudillo.companyregistry.application.site.SiteFingerprints;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup @ApplicationScoped
public class ConfiguredSiteFingerprints implements SiteFingerprints {
    private final HmacKeys keys;
    @Inject public ConfiguredSiteFingerprints(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }
    public ConfiguredSiteFingerprints(HmacKeys keys) { this.keys = keys; }
    @Override public Signature sign(SiteRegistration.Command command) {
        return new Signature(SiteCreationFingerprintV1.VERSION, keys.activeId(), SiteCreationFingerprintV1.sign(command, keys.secret(keys.activeId())));
    }
    @Override public boolean matches(SiteRegistration.Command command, short version, String keyId, String hash) {
        if (version != SiteCreationFingerprintV1.VERSION) throw new IllegalStateException("historical-fingerprint-policy-unavailable");
        return SiteCreationFingerprintV1.matches(command, keys.secret(keyId), hash);
    }
}
