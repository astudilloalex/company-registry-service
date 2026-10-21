package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.ContactFingerprints;
import com.alexastudillo.companyregistry.application.site.ContactMutation;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class ConfiguredContactFingerprints implements ContactFingerprints {
    private final HmacKeys keys;

    @Inject
    public ConfiguredContactFingerprints(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }

    public ConfiguredContactFingerprints(HmacKeys keys) { this.keys = keys; }

    @Override public Signature sign(ContactMutation.Command command) {
        return new Signature(ContactFingerprintV1.VERSION, keys.activeId(),
                ContactFingerprintV1.sign(command, keys.secret(keys.activeId())));
    }

    @Override public boolean matches(ContactMutation.Command command, short version, String keyId, String hash) {
        if (version != ContactFingerprintV1.VERSION) throw new IllegalStateException("historical-fingerprint-policy-unavailable");
        return ContactFingerprintV1.matches(command, keys.secret(keyId), hash);
    }
}
