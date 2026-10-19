package com.alexastudillo.companyregistry.infrastructure.persistence;

import com.alexastudillo.companyregistry.application.site.AddressFingerprints;
import com.alexastudillo.companyregistry.application.site.AddressMutation;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class ConfiguredAddressFingerprints implements AddressFingerprints {
    private final HmacKeys keys;

    @Inject
    public ConfiguredAddressFingerprints(FingerprintConfiguration configuration) {
        this(new HmacKeys(configuration.activeKeyId(), configuration.keys()));
    }

    public ConfiguredAddressFingerprints(HmacKeys keys) { this.keys = keys; }

    @Override public Signature sign(AddressMutation.Command command) {
        return new Signature(AddressFingerprintV1.VERSION, keys.activeId(),
                AddressFingerprintV1.sign(command, keys.secret(keys.activeId())));
    }

    @Override public boolean matches(AddressMutation.Command command, short version, String keyId, String hash) {
        if (version != AddressFingerprintV1.VERSION) throw new IllegalStateException("historical-fingerprint-policy-unavailable");
        return AddressFingerprintV1.matches(command, keys.secret(keyId), hash);
    }
}
