package com.alexastudillo.companyregistry.infrastructure.persistence;

import io.smallrye.config.ConfigMapping;
import java.util.Map;

@ConfigMapping(prefix = "company-registry.idempotency")
public interface FingerprintConfiguration {
    String activeKeyId();
    Map<String, String> keys();
}
